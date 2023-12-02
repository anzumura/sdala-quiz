package quiz

import quiz.FileUtils._
import quiz.ListFile._
import quiz.UnicodeUtils.isKanji

import java.nio.file.Path
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.{Try, Using}

/**
 * holds data loaded from a file with Kanji string entries
 *
 * Entries can be specified either one per line or multiple per line separated
 * by space. Uniqueness is verified when data is loaded and entries are stored
 * in order in a list. There are derived classes for specific data types, i.e.,
 * where all entries are for a 'JLPT Level' or a 'Kentei Kyu'.
 */
class ListFile protected (path: Path, fileType: FileType,
    nameIn: Option[String] = None)
    extends ThrowsDomainException {

  /**
   * @return name assigned at construction or if no name was given then return
   *         the capitalized file name (without extensions)
   */
  val name: String = nameIn.getOrElse(fileNameStem(path).capitalize)

  /**
   * list of all entries in the file
   */
  lazy val entries: Vector[String] = {
    val result = ArrayBuffer.empty[String]
    Using(Source.fromFile(path.toFile)) { source =>
      val uniqueEntries = mutable.Set.empty[String]
      source.getLines().zipWithIndex.foreach { case (line, i) =>
        def err(msg: String) =
          error(s"$msg - file: ${fileName(path)}, line: ${i + 1}")
        def add(entry: String): Unit = {
          if (!uniqueEntries.add(entry)) err(s"duplicate entry '$entry'")
          Try(if (validate(entry)) result += entry).failed.foreach(e =>
            err(e.getMessage)
          )
        }
        if (fileType == OnePerLine) {
          if (line.contains(' ')) err("line has multiple entries")
          add(line)
        } else line.split(' ').foreach(add)
      }
    }.failed.foreach(throw _)
    result.toVector
  }

  /**
   * @return number of entries loaded
   */
  def size: Int = entries.size

  /**
   * @param value the value to lookup
   * @return index starting at 0 or None if `name` is not found
   */
  def index(value: String): Option[Int] = entryIndex.get(value)

  /**
   * @param value the value to check
   * @return true if value is contained in this file
   */
  def exists(value: String): Boolean = entryIndex.contains(value)

  /**
   * derived classes can override this method to add validation. A derived class
   * can return true to allow adding the entry, false to silently skip adding it
   * or throw an exception which will be caught and rethrown by the base class
   *
   * @param entry entry to validate (base class always returns true)
   * @return true if the entry should be added
   */
  protected def validate(entry: String): Boolean = true

  private lazy val entryIndex = entries.zipWithIndex.toMap
}

object ListFile {
  sealed trait FileType
  case object OnePerLine extends FileType
  case object MultiplePerLine extends FileType

  def apply(path: Path) = new ListFile(path, OnePerLine)
  def apply(path: Path, fileType: FileType) = new ListFile(path, fileType)
  def apply(path: Path, name: String, fileType: FileType = OnePerLine) =
    new ListFile(path, fileType, Option(name))
}

/**
 * derived class of ListFile that ensures each entry is a recognized Kanji
 */
class KanjiListFile protected (path: Path, fileType: FileType,
    nameIn: Option[String] = None)
    extends ListFile(path, fileType, nameIn) {
  override protected def validate(entry: String): Boolean =
    isKanji(entry) || error(s"'$entry' is not a recognized Kanji")
}

object KanjiListFile {
  def apply(path: Path) = new KanjiListFile(path, OnePerLine)
  def apply(path: Path, fileType: FileType) = new KanjiListFile(path, fileType)
  def apply(path: Path, name: String, fileType: FileType = OnePerLine) =
    new KanjiListFile(path, fileType, Option(name))
}

/**
 * derived class of KanjiList file that ensures each entry is unique across
 * all files for the same 'Enumeration' type, i.e., an entry can't be in more
 * than one JLPT 'Level' file
 */
final class EnumListFile[T <: Enumeration: ClassTag] private (dir: Path,
    val value: T#Value)
    extends KanjiListFile(dir.resolve(value.toString + TextFileExtension),
      MultiplePerLine) {

  /**
   * the name of the enum type without the trailing '$' so if this class was
   * created with value `Kyu.K10` enumName would be "Kyu"
   */
  val enumName: String =
    implicitly[ClassTag[T]].runtimeClass.getSimpleName.dropRight(1)

  private val enumEntries =
    EnumListFile.entries.getOrElseUpdate(enumName, mutable.Set[String]())

  override protected def validate(
      entry: String): Boolean = super.validate(entry) && enumEntries.add(
    entry) || error(s"'$entry' already in another $enumName")
}

object EnumListFile {
  /**
   * @param dir the directory containing the enum file
   * @param value enum value, i.e., Level.N3
   * @return EnumListFile (file name is `value` plus ".txt", e.g., "N3.txt")
   */
  def apply[T <: Enumeration: ClassTag](
      dir: Path, value: T#Value): EnumListFile[T] = new EnumListFile(dir, value)

  /**
   * `entries` is used to ensure file entries are unique per enum type. It is a
   * map of 'enum name' (like "Level" or "Kyu") to a set entries
   */
  private val entries = mutable.Map[String, mutable.Set[String]]()
}