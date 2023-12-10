package quiz.kanji

import quiz.kanji.RadicalData.{MaxRadical, Radical, RadicalFileName}
import quiz.utils.ColumnFile.Column
import quiz.utils.{ColumnFile, ThrowsDomainException}

import java.nio.file.Path

class RadicalData(dir: Path) extends ThrowsDomainException {
  def findByName(s: String): Option[Radical] = data.get(s)
  def findByNumber(i: Int): Radical =
    // call data.nonEmpty to make sure lazy val is initialized
    if (data.nonEmpty && i > 0 && i <= MaxRadical) radicals(i - 1)
    else error(s"invalid Radical number '$i' (must be between 1 and $MaxRadical)")

  // this method is protected to help support testing
  protected def verifyDataSize(x: Int): Unit =
    if (x != MaxRadical) domainError(s"loaded $x, but expected " + s"$MaxRadical")

  private lazy val data = {
    val numberCol = Column("Number")
    val nameCol = Column("Name")
    val longNameCol = Column("LongName")
    val readingCol = Column("Reading")
    val f = ColumnFile(dir.resolve(RadicalFileName), numberCol, nameCol, longNameCol, readingCol)
    val d = f.processRows(Map.empty[String, Radical])(result =>
      f.get(nameCol).split(" ").toList match {
        case name :: altNames if name.nonEmpty =>
          val number = f.getUInt(numberCol, 214)
          if (number == 0) error("Radical number can't be zero")
          if (Option(radicals(number - 1)).nonEmpty) error(s"duplicate Radical number '$number'")
          val radical = Radical(number, name, altNames, f.get(longNameCol), f.get(readingCol))
          radicals(number - 1) = radical
          result + (name -> radical)
        case _ => error("Radical name can't be empty")
      })
    verifyDataSize(d.size)
    d
  }
  private val radicals = new Array[Radical](MaxRadical)
}

object RadicalData {
  val RadicalFileName = "radicals.txt"
  val MaxRadical = 214

  case class Radical(number: Int, name: String, altNames: List[String], longName: String,
      reading: String)
}
