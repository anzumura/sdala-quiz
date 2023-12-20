package quiz

import quiz.data.KanjiDataSanityTest.data
import quiz.kanji.{Grade, Kyu, Level}
import quiz.utils.BaseChoiceTest

class QuizTest extends BaseChoiceTest {
  private def quiz(input: String) = {
    val (choice, os) = create(input)
    new Quiz(data, choice, false).start()
    os.toString
  }

  "prompt for choosing quiz type" in {
    assert(quiz("q") == "Quiz Type (f=Frequency, g=Grade, k=Kyu, l=Level, q=quit) def 'l': ")
  }

  "prompt for Frequency quiz" in {
    assert(quiz("f\nq").endsWith("Frequency buckets of 250 (0=most frequent, 1-9, q=quit): "))
  }

  "prompt for Grade quiz" in {
    assert(quiz("g\nq").endsWith("Grade (1-6, q=quit, s=Secondary School) def 's': "))
  }

  "prompt for Level quiz" in { assert(quiz("l\nq").endsWith("JLPT Level (1-5, q=quit) def '1': ")) }

  "prompt for Kyu quiz" in {
    assert(quiz("k\nq").endsWith("Kentei Kyu (0=k10, 1-9, j=Jun-K2, k=Jun-K1, q=quit): "))
  }

  "prompt for list order" in {
    assert(quiz("f\n0\nq")
        .endsWith("List order (b=from beginning, e=from end, q=quit, r=random) def 'r': "))
  }

  "list from beginning" in {
    assert(quiz("f\n0\nb\nq")
        .contains("Question 1 of 250 (score 0): Kanji 日. Rad 日(72), Strokes 4, G1, N5, K10"))
  }

  "list from end" in {
    assert(quiz("f\n0\ne\nq")
        .contains("Question 1 of 250 (score 0): Kanji 価. Rad 人(9), Strokes 8, G5, N2, K6"))
  }

  "list in random order (seed is constant for test code)" in {
    assert(quiz("f\n0\nr\nq")
        .contains("Question 1 of 250 (score 0): Kanji 安. Rad 宀(40), Strokes 6, G3, N5, K8"))
  }

  "question contains 4 choices followed by a prompt" in {
    assert(quiz("f\n0\nb\nq").contains("""
        |  1: ガ、カク
        |  2: ニチ、ジツ、ひ、か
        |  3: ム、つと-める、つと-まる
        |  4: レン、つら-なる、つら-ねる、つ-れる
        |Choose reading (-=show meanings, 1-4, q=quit): """.stripMargin))
  }

  "Frequency buckets 0-8 have 250 entries and bucket 9 has 251" in (0 to 9).foreach(x =>
    assert(quiz(s"f\n$x\nb\nq").contains("Question 1 of 25" + (if (x == 9) 1 else 0))))

  "Grades have expected sizes" in Grade.defined.foreach(x =>
    assert(quiz(s"g\n${if (x == Grade.S) 's' else x.toString.last}\nb\nq")
        .contains("Question 1 of " + data.gradeMap(x).size)))

  "Levels have expected sizes" in Level.defined.foreach(x =>
    assert(quiz(s"l\n${x.toString.last}\nb\nq").contains("Question 1 of " + data.levelMap(x).size)))

  "Kyus have expected sizes" in Kyu.defined.foreach(x =>
    assert(quiz(s"k\n${x match {
        case Kyu.K10 => '0'
        case Kyu.KJ2 => 'j'
        case Kyu.KJ1 => 'k'
        case _ => x.toString.last
      }}\nb\nq").contains("Question 1 of " + data.kyuMap(x).size)))

  "correct answer increases score" in {
    val correct = 2 // can rely on this since random seed is constant for test code
    (1 to 4).foreach(x =>
      assert(quiz(s"f\n0\nb\n$x\nq")
          .contains(s"Question 2 of 250 (score ${if (x == correct) 1 else 0})")))
  }

  "incorrect answer results in a message to the user containing the correct answer" in {
    assert(quiz("f\n0\nb\n1\nq").contains("  Incorrect: the answer is 2"))
  }

  "final score should be 0/0 if user quits before answering the first question" in {
    assert(quiz("f\n0\nb\nq").contains(">>> Final score: 0/0\n"))
  }

  "final score should be 1/1 after answering one question correctly and then quitting" in {
    assert(quiz("f\n0\nb\n2\nq").contains(">>> Final score: 1/1\n"))
  }

  "final score should be 0/1 and show mistakes after answering a question incorrectly" in {
    assert(quiz("f\n0\nb\n1\nq").contains(">>> Final score: 0/1 mistakes: 日\n"))
  }
}
