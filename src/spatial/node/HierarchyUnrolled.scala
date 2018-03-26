package spatial.node

import argon._
import spatial.lang._

/** Banked accessors */
abstract class BankedAccessor[A:Type,R:Type] extends EnPrimitive[R] {
  val A: Type[A] = Type[A]
  def bankedRead: Option[BankedRead]
  def bankedWrite: Option[BankedWrite]
  final var ens: Set[Bit] = Set.empty

  def mem: Sym[_]
  def bank: Seq[Seq[Idx]]
  def ofs: Seq[Idx]
  var enss: Seq[Set[Bit]]
  def width: Int = bank.length

  override def mirrorEn(f: Tx, addEns: Set[Bit]): Op[R] = {
    enss = enss.map{ens => ens ++ addEns}
    this.mirror(f)
  }
  override def updateEn(f: Tx, addEns: Set[Bit]): Unit = {
    enss = enss.map{ens => ens ++ addEns}
    this.update(f)
  }
}

abstract class BankedReader[A:Bits](implicit vT: Type[Vec[A]]) extends BankedAccessor[A,Vec[A]] {
  def bankedRead = Some(BankedRead(mem,bank,ofs,enss))
  def bankedWrite: Option[BankedWrite] = None
}

abstract class BankedDequeue[A:Bits](implicit vT: Type[Vec[A]]) extends BankedReader[A] {
  override def effects: Effects = Effects.Writes(mem)
  def bank: Seq[Seq[Idx]] = Nil
  def ofs: Seq[Idx] = Nil
}


abstract class BankedWriter[A:Type] extends BankedAccessor[A,Void] {
  override def effects: Effects = Effects.Writes(mem)
  def data: Seq[Sym[_]]
  def bankedRead: Option[BankedRead] = None
  def bankedWrite = Some(BankedWrite(mem,data,bank,ofs,enss))
}

abstract class BankedEnqueue[A:Type] extends BankedWriter[A] {
  def bank: Seq[Seq[Idx]] = Nil
  def ofs: Seq[Idx] = Nil
}