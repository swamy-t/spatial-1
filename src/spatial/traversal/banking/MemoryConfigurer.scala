package spatial.traversal
package banking

import argon._
import poly.ISL
import utils.implicits.collections._

import spatial.issues.UnbankableGroup
import spatial.data._
import spatial.lang._
import spatial.util._
import spatial.internal.spatialConfig

import scala.collection.mutable.ArrayBuffer

class MemoryConfigurer[+C[_]](mem: Mem[_,C], strategy: BankingStrategy)(implicit state: State, isl: ISL) {
  protected val rank: Int = rankOf(mem).length
  protected val isGlobal: Boolean = mem.isArgIn || mem.isArgOut

  // TODO: This may need to be tweaked based on the fix for issue #23
  final val FLAT_BANKS = Seq(List.tabulate(rank){i => i})
  final val NEST_BANKS = List.tabulate(rank){i => Seq(i)}
  val dimGrps: Seq[Seq[Seq[Int]]] = if (rank > 1) Seq(FLAT_BANKS, NEST_BANKS) else Seq(FLAT_BANKS)

  def configure(): Unit = {
    dbg("\n\n--------------------------------")
    dbg(s"${mem.ctx}: Inferring instances for memory ${mem.fullname}")
    dbg(mem.ctx.content.getOrElse(""))
    val readers = mem.readers
    val writers = mem.writers
    resetData(readers, writers)

    val readMatrices = readers.flatMap{rd => rd.affineMatrices }
    val writeMatrices = writers.flatMap{wr => wr.affineMatrices }

    val instances = bank(readMatrices, writeMatrices)
    summarize(instances)
    finalize(instances)
  }

  protected def resetData(readers: Set[Sym[_]], writers: Set[Sym[_]]): Unit = {
    (readers.iterator ++ writers.iterator).foreach{a =>
      metadata.clear[Dispatch](a)
      metadata.clear[Ports](a)
    }
    metadata.clear[Duplicates](mem)
  }

  protected def summarize(instances: Seq[Instance]): Unit = {
    dbg(s"---------------------------------------------------------------------")
    dbg(s"Name: ${mem.fullname}")
    dbg(s"Type: ${mem.tp}")
    dbg(s"Src:  ${mem.ctx}")
    dbg(s"Src:  ${mem.ctx.content.getOrElse("<???>")}")
    dbg(s"---------------------------------------------------------------------")
    dbg(s"Symbol:     ${stm(mem)}")
    dbg(s"Instances: ${instances.length}")
    instances.zipWithIndex.foreach{case (inst,i) =>
      dbg(s"Instance #$i")
      dbgss("  ", inst)
      dbg("\n\n")
    }
    dbg(s"---------------------------------------------------------------------")
    dbg("\n\n\n")
  }

  /** Complete memory analysis by adding banking and buffering metadata to the memory and
    * all associated accesses.
    */
  protected def finalize(instances: Seq[Instance]): Unit = {
    val duplicates = instances.map{_.toMemory}
    mem.duplicates = duplicates

    instances.zipWithIndex.foreach{case (inst, id) =>
      (inst.reads.iterator.flatten ++ inst.writes.iterator.flatten).foreach{a =>
        a.access.ports += (a.unroll -> inst.ports(a))
        a.access.addDispatch(a.unroll, id)
        dbgs(s"  Added port ${inst.ports(a)} to ${a.access} {${a.unroll.mkString(",")}}")
        dbgs(s"  Added dispatch $id to ${a.access} {${a.unroll.mkString(",")}}")
      }
    }

    val used = instances.flatMap(_.accesses).toSet
    val unused = mem.accesses diff used
    unused.foreach{access =>
      val msg = if (access.isReader) s"Read of ${mem.nameOr("memory")} was unused. Read will be removed."
                else s"Write to memory ${mem.nameOr("memory")} is never used. Write will be removed."
      warn(access.ctx, msg)
      warn(access.ctx)

      access.isUnusedAccess = true

      dbgs(s"  Unused access: ${stm(access)}")
    }
  }

  /** Group accesses on this memory.
    * An access a is grouped with a set of accesses S if there exists some b in S such that:
    *   [Control] a and b occur simultaneously (in Parallel or pipeline parallel)
    *   [Space]   and a and b are guaranteed to never hit the same address
    * Otherwise a is placed in a new group
    */
  protected def groupAccesses(accesses: Set[AccessMatrix], tp: String): Set[Set[AccessMatrix]] = {
    val groups = ArrayBuffer[Set[AccessMatrix]]()

    dbgs(s"  Grouping ${accesses.size} ${tp}s: ")

    accesses.foreach{a =>
      dbg(s"    Access: ${a.access} [${a.parent}]")
      val grpId = {
        if (a.parent == Host) { if (groups.isEmpty) -1 else 0 }
        else groups.zipWithIndex.indexWhere{case (grp, i) =>
          val pairs = grp.filter{b => requireConcurrentPortAccess(a, b) && !a.overlapsAddress(b) }
          if (pairs.nonEmpty) dbg(s"      Group #$i: ")
          else                dbg(s"      Group #$i: <none>")
          pairs.foreach{b => dbgs(s"        ${b.access} [${b.parent}]") }
          pairs.nonEmpty
        }
      }
      if (grpId != -1) { groups(grpId) = groups(grpId) + a } else { groups += Set(a) }
    }

    if (config.enDbg) {
      if (groups.isEmpty) dbg(s"\n  <No $tp Groups>") else dbg(s"  ${groups.length} $tp Groups:")
      groups.zipWithIndex.foreach { case (grp, i) =>
        dbg(s"  Group #$i")
        grp.foreach{matrix => dbgss("    ", matrix) }
      }
    }
    groups.toSet
  }

  /** True if accesses a and b may occur concurrently and to the same buffer port.
    * This is true when any of the following hold:
    *   0. a and b are two different unrolled parts of the same node
    *   1. a and b are in the same inner pipeline
    *   2. a and b are in the same fully unrolled inner sequential
    *   3. a and b are in a Parallel controller
    *   4. TODO[2]: If a and b are in parallel stages in a controller's child dataflow graph
    */
  def requireConcurrentPortAccess(a: AccessMatrix, b: AccessMatrix): Boolean = {
    val lca = LCA(a.access, b.access)
    (a.access == b.access && a.unroll != b.unroll) ||
      lca.isInnerPipeLoop ||
      (lca.isInnerSeqControl && lca.isFullyUnrolledLoop) ||
      lca.isParallel
  }


  protected def bank(readers: Set[AccessMatrix], writers: Set[AccessMatrix]): Seq[Instance] = {
    val rdGroups = groupAccesses(readers, "Read")
    val wrGroups = groupAccesses(writers, "Write")
    if      (readers.nonEmpty) mergeReadGroups(rdGroups, wrGroups)
    else if (writers.nonEmpty) mergeWriteGroups(wrGroups)
    else Seq(Instance.Unit(rank))
  }

  /** Returns an approximation of the cost for the given banking strategy. */
  def cost(banking: Seq[Banking], depth: Int): Int = {
    val totalBanks = banking.map(_.nBanks).product
    depth * totalBanks
  }

  /** Computes the Ports for all accesses in all groups
    *
    *            |--------------|--------------|
    *            |   Buffer 0   |   Buffer 1   |
    *            |--------------|--------------|
    * bufferPort         0              1           The buffer port (None for access outside pipeline)
    * muxSize            3              3           Width of a single time multiplexed vector
    *                 |x x x|        |x x x|
    *
    *                /       \      /       \
    *
    *              |x x x|x x|     |x x x|x x x|
    * muxPort         0    1          0     1       The ID for the given time multiplexed vector
    *
    *              |( ) O|O O|    |(   )|( ) O|
    * muxOfs        0   2 0 1        0    0  2      Start offset into the time multiplexed vector
    *
    */
  protected def computePorts(groups: Set[Set[AccessMatrix]], bufPorts: Map[Sym[_],Option[Int]]): Map[AccessMatrix,Port] = {
    var ports: Map[AccessMatrix,Port] = Map.empty

    val bufferPorts: Map[Option[Int],Set[Set[AccessMatrix]]] = groups.flatMap{grp =>
      grp.groupBy{m => bufPorts(m.access) }.toSeq // These should generally always be all the same?
    }.groupBy(_._1).mapValues(_.map(_._2))

    bufferPorts.foreach{case (bufferPort, grps) =>
      var muxPortMap: Map[AccessMatrix,Int] = Map.empty

      // Each group is banked together, so all accesses in a group must be connected to the same muxPort
      grps.zipWithIndex.foreach{case (grp,muxPort) =>
        grp.foreach{m => muxPortMap += m -> muxPort }
      }

      val muxPorts: Map[Int,Set[AccessMatrix]] = grps.flatten.groupBy{m => muxPortMap(m) }
      val muxSize: Int = muxPorts.values.map(_.size).maxOrElse(0)

      muxPorts.foreach{case (muxPort, matrices) =>
        var muxOfs: Int = 0
        matrices.groupBy(_.access).values.foreach{mats =>
          import scala.math.Ordering.Implicits._
          mats.toSeq.sortBy(_.unroll).foreach{m =>
            val port = Port(
              bufferPort = bufferPort,
              muxPort    = muxPort,
              muxSize    = muxSize,
              muxOfs     = muxOfs,
              broadcast  = 0
            )
            ports += m -> port
            muxOfs += 1
          }
        }
      }
    }

    ports
  }

  /** Returns the memory instance required to support the given read and write sets.
    * Includes banking, buffering depth, and the mapping of accesses to buffer ports.
    * Also calculates whether the associated memory should be considered a "buffer accumulator";
    * this occurs if at least one read and write occur in the same controller within a buffer.
    */
  protected def bankGroups(rdGroups: Set[Set[AccessMatrix]], wrGroups: Set[Set[AccessMatrix]]): Option[Instance] = {
    val reads = rdGroups.flatten
    val writes = wrGroups.flatten
    val ctrls = reads.map(_.parent)
    val reaching = reachingWrites(reads,writes,isGlobal)
    val reachingWrGroups = wrGroups.map{grp => grp intersect reaching }.filterNot(_.isEmpty)
    val bankings = strategy.bankAccesses(mem, rank, rdGroups, reachingWrGroups, dimGrps)
    if (bankings.nonEmpty) {
      val (metapipe, bufPorts) = findMetaPipe(mem, reads.map(_.access), writes.map(_.access))
      val depth = bufPorts.values.collect{case Some(p) => p}.maxOrElse(0) + 1
      val bankingCosts = bankings.map{b => b -> cost(b,depth) }
      val (banking, bankCost) = bankingCosts.minBy(_._2)
      // TODO[5]: Assumption: All memories are at least simple dual port
      val ports = computePorts(rdGroups,bufPorts) ++ computePorts(wrGroups,bufPorts)
      val isBuffAccum = metapipe.isDefined &&
                        writes.cross(reads).exists{case (wr,rd) => rd.parent == wr.parent }
      val accum = if (isBuffAccum) AccumType.Buff else AccumType.None
      val accTyp = mem.accumType | accum

      val instance = Instance(rdGroups,reachingWrGroups,ctrls,metapipe,banking,depth,bankCost,ports,accTyp)

      dbgs(s"  Reads:")
      rdGroups.foreach{grp => grp.foreach{m => dbgss("    ", m) }}
      dbgs(s"  Writes:")
      wrGroups.foreach{grp => grp.foreach{m => dbgss("    ", m) }}
      dbgs(s"  Instance: ")
      dbgss("  ", instance)

      Some(instance)
    }
    else None
  }

  /** Should not attempt to merge instances if any of the following conditions hold:
    *   1. The two instances have a common LCA controller
    *   2. The two instances result in hierarchical buffers (for now)
    *   3. Either instance is a Fold or Buffer "accumulator"
    */
  protected def getMergeAttemptError(a: Instance, b: Instance): Option[String] = {
    lazy val reads = a.reads.flatten ++ b.reads.flatten
    lazy val writes = a.writes.flatten ++ b.writes.flatten
    lazy val metapipes = findAllMetaPipes(reads.map(_.access), writes.map(_.access)).keys

    if ((a.ctrls intersect b.ctrls).nonEmpty && !isGlobal) Some("Control conflict")
    else if ((a.accType | b.accType) >= AccumType.Buff)    Some(s"Accumulator conflict (A Type: ${a.accType}, B Type: ${b.accType})")
    else if (metapipes.size > 1)                           Some("Ambiguous metapipes")
    else None
  }

  /** Should not complete merging instances if any of the following hold:
    *   1. The merge was not successful
    *   2. The merge results in a multi-ported N-buffer (if this is disabled)
    *   3. The merged instance costs more than the total cost of the two separate instances
    */
  protected def getMergeError(i1: Instance, i2: Instance, i3: Option[Instance]): Option[String] = {
    if (i3.isEmpty) Some("Banking conflict")
    else if (i1.metapipe.isDefined && i2.metapipe.isDefined && !spatialConfig.enableBufferCoalescing) Some("Buffer conflict")
    else if (i3.get.cost > (i1.cost + i2.cost)) Some("Expensive")
    else None
  }

  /** Greedily banks and merges groups of readers into memory instances. */
  protected def mergeReadGroups(rdGroups: Set[Set[AccessMatrix]], wrGroups: Set[Set[AccessMatrix]]): Seq[Instance] = {
    val instances = ArrayBuffer[Instance]()

    rdGroups.zipWithIndex.foreach{case (grp,grpId) =>
      bankGroups(Set(grp),wrGroups) match {
        case Some(i1) =>
          var instIdx = 0
          var merged = false
          while (instIdx < instances.length && !merged) {
            val i2 = instances(instIdx)

            val err = getMergeAttemptError(i1, i2)
            if (err.isEmpty) {
              val i3 = bankGroups(i1.reads ++ i2.reads, wrGroups)
              val err = getMergeError(i1, i2, i3)
              if (err.isEmpty) {
                instances(instIdx) = i3.get
                merged = true
                dbg(s"Merged $grpId into instance $instIdx")
              }
              else dbg(s"Did not merge $grpId into instance $instIdx: ${err.get}")
            }
            else dbg(s"Did not merge $grpId into instance $instIdx: ${err.get}")

            instIdx += 1
          }
          if (!merged) instances += i1

        case None =>
          val writeGroups = reachingWrites(grp, wrGroups.flatten, isGlobal)
          raiseIssue(UnbankableGroup(mem, grp, writeGroups))
      }
    }
    instances
  }

  /** Greedily banks and merges groups of writers into memory instances.
    * Only used if the memory has no readers.
    */
  protected def mergeWriteGroups(wrGroups: Set[Set[AccessMatrix]]): Seq[Instance] = {
    // Assumes that all writers reach some unknown reader external to Accel.
    val instance = bankGroups(Set.empty, wrGroups)
    if (instance.isEmpty) {
      raiseIssue(UnbankableGroup(mem,Set.empty,wrGroups.flatten))
      Nil
    }
    else Seq(instance.get)
  }
}
