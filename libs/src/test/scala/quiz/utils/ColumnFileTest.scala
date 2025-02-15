package quiz.utils

import cats.syntax.all.*
import org.scalatest.Assertion
import quiz.test.FileTest.testFileName
import quiz.test.{BaseTest, FileTest}
import quiz.utils.ColumnFile.*
import quiz.utils.ColumnFileTest.*

import java.io.IOException
import java.nio.file.{Files, Path}
import scala.util.Using

class ColumnFileTest extends FileTest:
  // tests below use a derived instance of ColumnFile for testing
  override val mainClassName: String = "TestColumnFile"

  "create" - {
    "one column" in { assert(create(List(col1), "col1").numColumns == 1) }

    "current row zero before any rows are requested" in {
      assert(create(List(col1), "col1").currentRow == 0)
    }

    "multiple columns" in { assert(create(cols.take(2), "col1\tcol2").numColumns == 2) }

    "allow file with extra columns (this is not allowed by default)" in {
      assert(create(AllowExtraCols.Yes, List(col1), "col1\tcolX").numColumns == 1)
    }

    "space delimited file" in { assert(create(' ', cols.take(2), "col1 col2").numColumns == 2) }
  }

  "create errors" - {
    "empty columns" in {
      error(ColumnFile(Path.of("")), "[ColumnFile] must specify at least one column")
    }

    "duplicate columns" in {
      error(ColumnFile(Path.of(""), col1, col1), s"[ColumnFile] duplicate column '$col1'")
    }

    "missing file" in {
      error(
        ColumnFile(Path.of("x"), col1), _.startsWith(s"[ColumnFile] failed to read header row: x"))
    }

    "missing header row" in { fileError(create(cols), "missing header row") }

    "duplicate header columns" in {
      fileError(create(cols, "col1\tcol1"), "duplicate header 'col1'")
    }

    "unrecognized header column" in {
      fileError(create(cols, "col11"), "unrecognized header 'col11'")
    }

    "one missing column" in { fileError(create(cols, "col1\tcol3"), "column 'col2' not found") }

    "multiple missing columns" in {
      fileError(create(cols, "col2"), "2 columns not found: 'col1', 'col3'")
    }
  }

  "processRows" - {
    "returns initial value if file has no data" in {
      val expected = "some value"
      assert(create(cols.take(1), "col1").processRows(expected)(_ + "x") == expected)
    }

    "op is called for each row" in {
      val f = create(cols.take(1), "col1", "A", "B", "C")
      assert(f.processRows(List[String]())(f.get(col1) :: _) == List("C", "B", "A"))
    }

    "exception from op is wrapped in a DomainException including the file name and line" in {
      val f = create(cols.take(1), "col1", "x", "y", "z")
      fileError(
        f.processRows(0)(if f.currentRow != 3 then _ else throw new Exception("some error")),
        "some error", 3)
    }

    "exception from same instance doesn't repeat file name, line number, etc." in {
      val f = create(cols.take(1), "col1", "2", "B", "7")
      fileError(
        f.processRows(List[Int]())(f.getUInt(col1) :: _), "convert to UInt failed", 2, col1, "B")
    }
  }

  "nextRow" - {
    "called after returning false" in {
      val f = create(List(col1), "col1")
      assert(!f.nextRow())
      domainError(f.nextRow(), s"file: '$testFileName' has been closed")
    }

    "called after close" in {
      val f = create(List(col1), "col1")
      f.close()
      domainError(f.nextRow(), s"file: '$testFileName' has been closed")
    }

    "too many columns" in {
      val f = create(List(col1), "col1", "A", "B\tC", "D")
      assert(f.nextRow())
      assert(f.currentRow == 1)
      assert(f.get(col1) == "A")
      // the second row has two values so an exception is thrown, but current
      // row is incremented so that processing can continue after the bad row
      fileError(f.nextRow(), "too many columns", 2)
      assert(f.currentRow == 2)
      // call nextRow to move to the third row and continue processing
      assert(f.nextRow())
      assert(f.currentRow == 3)
      assert(f.get(col1) == "D")
    }

    "not enough columns" in {
      val f = create(cols.take(2), "col1\tcol2", "A", "B\tC")
      fileError(f.nextRow(), "not enough columns", 1)
      // call nextRow to move to the second row and continue processing
      assert(f.nextRow())
      assert(f.currentRow == 2)
      assert(f.get(col1) == "B")
      assert(f.get(col2) == "C")
    }

    "failed read" in {
      val path = Files.createFile(testFile)
      Files.writeString(path, "col1\nA")
      Using.resource(new TestColumnFile(path, '\t', AllowExtraCols.No, col1)) { f =>
        f.readFailure = true
        fileError(f.nextRow(), "failed to read row: bad read")
      }
    }

    "failed close" in {
      val path = Files.createFile(testFile)
      Files.writeString(path, "col1")
      val f = new TestColumnFile(path, '\t', AllowExtraCols.No, col1)
      f.closeFailure = true
      domainError(f.nextRow(), s"failed to close: bad close")
    }
  }

  "get" - {
    "string value" in {
      val expected = "Val"
      val f = create(List(col1), "col1", expected)
      assert(f.nextRow())
      assert(f.get(col1) == expected)
    }

    "optional string value" in {
      val expected = "Val"
      val f = create(List(col1), "col1", s"$expected\n")
      assert(f.nextRow())
      assert(f.getOption(col1).contains(expected))
      assert(f.nextRow())
      assert(f.getOption(col1).isEmpty)
    }

    "get value from file with extra columns" in {
      val f = create(AllowExtraCols.Yes, List(col1), "col1\tcolX", "A\tB")
      assert(f.nextRow())
      assert(f.get(col1) == "A")
    }

    "values after nextRow returns false" in {
      val f = create(cols.take(2), "col1\tcol2", "A\tB")
      Seq(true, false).foreach { rowRetrieved =>
        assert(rowRetrieved == f.nextRow())
        assert(f.currentRow == 1) // row number doesn't change
        assert(f.get(col1) == "A")
        assert(f.get(col2) == "B")
      }
    }

    "empty values" in {
      val f = create(cols, "col1\tcol2\tcol3", "\tB\tC", "A\t\tC", "\t\t")
      f.nextRow() // first value is empty
      assert(f.get(col1).isEmpty)
      assert(f.get(col2) == "B")
      assert(f.get(col3) == "C")
      f.nextRow() // second value is empty
      assert(f.get(col1) == "A")
      assert(f.get(col2).isEmpty)
      assert(f.get(col3) == "C")
      f.nextRow() // all values are empty
      cols.foreach { c => assert(f.get(c).isEmpty) }
      // make sure all data has been read
      assert(!f.nextRow())
      assert(f.currentRow == 3)
    }

    "before nextRow fails" in {
      val f = create(List(col1), "col1", "Val")
      fileError(f.get(col1), "'nextRow' must be called before 'get'")
    }

    "column created after creating ColumnFile is 'unknown'" in {
      val f = create(List(col1), "col1", "Val")
      assert(f.nextRow())
      val c = Column("Created After")
      fileError(f.get(c), "unknown column 'Created After'", 1)
    }

    "column not included in ColumnFile is 'invalid'" in {
      val c = Column("Created Before")
      val f = create(List(col1), "col1", "Val")
      assert(f.nextRow())
      fileError(f.get(c), "invalid column 'Created Before'", 1)
    }

    "unsigned int value" in {
      val f = create(cols.take(2), "col1\tcol2", "0\t123")
      f.nextRow()
      assert(f.getUInt(col1) == 0)
      assert(f.getUInt(col2) == 123)
    }

    "invalid unsigned int" in {
      val f = create(cols.take(2), "col1\tcol2", "bad\t-123")
      f.nextRow()
      Seq((col1, "bad"), (col2, "-123"))
        .foreach((c, s) => fileError(f.getUInt(c), "convert to UInt failed", 1, c, s))
    }

    "unsigned int with max value" in {
      val f = create(List(col1), "col1", "0", "123")
      f.nextRow()
      assert(f.getUInt(col1, 0) == 0)
      f.nextRow()
      assert(f.getUInt(col1, -1) == 123)
      assert(f.getUInt(col1, 123) == 123)
      assert(f.getUInt(col1, Int.MaxValue) == 123)
    }

    "unsigned int exceeding max value" in {
      val f = create(List(col1), "col1", "18", "100")
      Seq((0, "18"), (99, "100")).foreach { (max, s) =>
        f.nextRow()
        fileError(f.getUInt(col1, max), s"exceeded max value $max", f.currentRow, col1, s)
      }
    }

    "unsigned int from empty column returns default" in {
      val f = create(cols.take(2), "col1\tcol2", "\t123")
      f.nextRow()
      assert(f.getUIntDefault(col1, 7) == 7)
      assert(f.getUIntDefault(col2, 8) == 123)
      // max check is not performed when returning default
      assert(f.getUIntDefault(col1, 7, max = 5) == 7)
    }

    "bool values" in {
      val f = create(cols, "col1\tcol2\tcol3", "Y\tT\tx", "N\tF\t")
      f.nextRow()
      assert(f.getBool(col1) && f.getBool(col2))
      fileError(f.getBool(col3), "convert to bool failed", 1, col3, "x")
      f.nextRow()
      cols.foreach(c => assert(!f.getBool(c)))
    }
  }

  override protected def afterEach(): Unit =
    testColumnFile.foreach(_.closeFile())
    super.afterEach()

  private def fileError(f: => Any, msg: String, row: Int, c: Column, s: String): Assertion =
    domainError(f, s"${fileMsg(msg, row, None)}, column: '$c', value: '$s'")

  private def create(sep: Char, allowExtraCols: AllowExtraCols, cols: List[Column],
      lines: String*) =
    testColumnFile.foreach(_.closeFile())
    val f = new TestColumnFile(writeTestFile(lines*), sep, allowExtraCols, cols*)
    testColumnFile = f.some
    f

  private def create(
      allowExtraCols: AllowExtraCols, cols: List[Column], lines: String*): ColumnFile = create(
    DefaultSeparator, allowExtraCols, cols, lines*)

  private def create(sep: Char, cols: List[Column], lines: String*): ColumnFile = create(
    sep, AllowExtraCols.No, cols, lines*)

  private def create(
      cols: List[Column], lines: String*): ColumnFile = create(AllowExtraCols.No, cols, lines*)

  private var testColumnFile = none[TestColumnFile]

class ColumnTest extends BaseTest:
  "toString returns column name" in { assert(col1.toString == "col1") }

  "number is assigned based on creation order" in {
    val c = (1 to 3).map(x => Column(s"Create-Order-$x"))
    c.iterator.sliding(2)
      .foreach(s => assert(s.headOption.map(_.number + 1) == s.lastOption.map(_.number)))
  }

  "number is unique per name" in { cols.foreach(c => assert(c.number == Column(c.name).number)) }

  "same name is considered equal" in {
    cols.foreach { c =>
      assert(c == Column(c.name))
      assert(c != Column(c.name + "x"))
    }
    var x: Any = col1.name
    assert(col1 != x) // classes are different
    x = col1
    assert(col1 == x) // classes are the same
  }

  "hasCode is based on number" in { assert(col1.hashCode == col1.number.hashCode) }

object ColumnFileTest:
  val cols: List[Column] = (1 to 3).map(c => Column("col" + c)).toList
  val col1 :: col2 :: col3 :: Nil = cols: @unchecked()

  private class TestColumnFile(path: Path, sep: Char, allowExtraCols: AllowExtraCols, cols: Column*)
  extends ColumnFile(path, sep, allowExtraCols, cols*) with AutoCloseable:
    // allow tests to force close or read to fail
    var closeFailure: Boolean = false
    var readFailure: Boolean = false

    // make close public so tests can force closing the underlying file
    override def closeFile(): Unit =
      super[ColumnFile].closeFile() // still want to close the real underlying file
      if closeFailure then throw new IOException("bad close")

    override protected def readRow(): String =
      if readFailure then throw new IOException("bad read")
      super.readRow()
