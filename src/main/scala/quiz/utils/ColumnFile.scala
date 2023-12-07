package quiz.utils

import quiz.utils.ColumnFile.*

import java.io.IOException
import java.nio.file.Path
import scala.collection.mutable
import scala.io.Source

/** class for loading data from a delimiter separated file with a header row
 *  containing the column names
 */
class ColumnFile protected (path: Path, val sep: Char, cols: Column*)
    extends ThrowsDomainException {
  if (cols.isEmpty) domainError("must specify at least one column")

  /** returns number of columns for this file */
  def numColumns: Int = rowValues.length

  /** returns current row number starting at 1, or 0 if [[nextRow]] hasn't been
   *  called yet
   */
  def currentRow: Int = _currentRow

  /** reads next row and must be called before calling ant [[get]] methods. If
   *  there's no more rows then false is returned and the file is closed - thus
   *  calling nextRow again after the file is closed raises an exception.
   *
   *  @return true if a row was read or false if there is no more data
   *  @throws DomainException if reading the next row fails or has incorrect
   *                          number of columns
   */
  def nextRow(): Boolean = {
    if (_closed) domainError(s"file: '$fileName' has been closed")
    val hasNext = lines.hasNext
    if (hasNext) processNextRow()
    else
      try {
        close()
        _closed = true
      } catch {
        case e: IOException => domainError("failed to close: " + e.getMessage)
      }
    hasNext
  }

  /** @param col column contained in this file
   *  @return string value for the given column in current row
   *  @throws DomainException if [[nextRow]] hasn't been called yet or the given
   *                          column isn't part of this file
   */
  def get(col: Column): String = {
    if (_currentRow == 0) fileError("'nextRow' must be called before 'get'")
    if (col.number >= columnToPos.length) fileError(s"unknown column '$col'")
    val pos = columnToPos(col.number)
    if (pos == ColumnNotFound) fileError(s"invalid column '$col'")
    rowValues(pos)
  }

  /** @param col column contained in this file
   *  @param max max value allowed (only checks if max is non-negative)
   *  @return unsigned int value for the given column in current row
   *  @throws DomainException if [[get]] fails or value can't be converted to an
   *                          unsigned int less than or equal to max
   */
  def getUInt(col: Column, max: Int = NoMaxValue): Int =
    processUInt(get(col), col, max)

  /** similar to [[getUInt]], but if value stored at `col` is empty then return
   *  `default`, note, max check is not performed when `default` is used
   */
  def getUIntDefault(col: Column, default: Int, max: Int = NoMaxValue): Int = {
    val s = get(col)
    if (s.isEmpty) default else processUInt(s, col, max)
  }

  /** @param col column contained in this file
   *  @return true for "Y" or "T", false for "N", "F" or ""
   *  @throws DomainException if [[get]] fails or value is unrecognized
   */
  def getBool(col: Column): Boolean = get(col) match {
    case "Y" | "T" => true
    case "N" | "F" | "" => false
    case s => fileError("convert to bool failed", col, s)
  }

  // methods to help support testing
  protected def readRow(): String = lines.next()
  protected def close(): Unit = source.close()

  private def fileError(msg: String) = domainError(errorMsg(msg))
  private def fileError(msg: String, column: Column, s: String) =
    domainError(errorMsg(msg) + s", column: '$column', value: '$s'")

  private def errorMsg(msg: String) = {
    val result = s"$msg - file: $fileName"
    if (currentRow > 0) s"$result, line: $currentRow" else result
  }

  private def processHeaderRow(source: Source,
      colsIn: mutable.Map[String, Column]) =
    try {
      val lines = source.getLines()
      if (!lines.hasNext) fileError("missing header row")
      val colsFound = mutable.Set.empty[String]
      lines.next().split(sep).zipWithIndex.foreach { case (s, pos) =>
        if (!colsFound.add(s)) fileError(s"duplicate header '$s'")
        colsIn.remove(s) match {
          case Some(c) => columnToPos(c.number) = pos
          case _ => fileError(s"unrecognized header '$s'")
        }
      }
      colsIn.size match {
        case 0 => (source, lines)
        case 1 => fileError(s"column '${colsIn.keys.mkString}' not found")
        case s => fileError(colsIn.keys.toIndexedSeq.sorted.mkString(
              s"$s columns not found: '", "', '", "'"))
      }
    } catch {
      case e: DomainException =>
        source.close
        throw e
    }

  private def processNextRow(): Unit =
    try {
      val vals = readRow().split(splitRegex, -1)
      _currentRow += 1
      vals.length match {
        case l if l < numColumns => fileError("not enough columns")
        case l if l > numColumns => fileError("too many columns")
        case _ => vals.zipWithIndex.foreach { case (s, i) => rowValues(i) = s }
      }
    } catch {
      case e: IOException => fileError(s"failed to read row: ${e.getMessage}")
    }

  private def processUInt(s: String, col: Column, max: Int) = (try {
    Integer.parseUnsignedInt(s)
  } catch {
    case _: Throwable => fileError("convert to UInt failed", col, s)
  }) match {
    case x if max >= 0 && max < x =>
      fileError(s"exceeded max value $max", col, s)
    case x => x
  }

  private val fileName = path.getFileName.toString
  private val rowValues = new Array[String](cols.size)
  private val columnToPos = Array.fill(allColumns.size)(ColumnNotFound)
  private val splitRegex = sep.toString // needed for regex split function
  private var _currentRow = 0
  private var _closed = false

  private val (source, lines) = {
    val colsIn = mutable.Map.empty[String, Column]
    cols.foreach(c =>
      if (colsIn.put(c.name, c).nonEmpty) domainError(s"duplicate column '$c'")
    )
    try {
      processHeaderRow(Source.fromFile(path.toFile), colsIn)
    } catch {
      case e: IOException =>
        domainError("failed to read header row: " + e.getMessage)
    }
  }
}

object ColumnFile {
  val DefaultSeparator: Char = '\t'

  def apply(path: Path, sep: Char, cols: Column*): ColumnFile =
    new ColumnFile(path, sep, cols: _*)
  def apply(path: Path, cols: Column*): ColumnFile =
    apply(path, DefaultSeparator, cols: _*)

  private val allColumns = mutable.HashMap.empty[String, Int]
  private val ColumnNotFound, NoMaxValue = -1

  /** represents a column in a [[ColumnFile]]. Instances are used to get
   *  values from each row and the same Column instance can be used in multiple
   *  ColumnFiles.
   */
  final class Column private (val name: String) {
    val number: Int = allColumns.getOrElseUpdate(name, allColumns.size)

    override def equals(rhs: Any): Boolean = rhs match {
      case c: Column => number == c.number
      case _ => false
    }
    override def hashCode: Int = number.hashCode
    override def toString: String = name
  }
  object Column { def apply(name: String): Column = new Column(name) }
}