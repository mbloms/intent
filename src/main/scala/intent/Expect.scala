package intent

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import scala.collection.IterableOnce
import scala.collection.mutable.ListBuffer
import scala.language.implicitConversions

trait Expectation {
  def evaluate(): Future[ExpectationResult]
}

sealed trait ExpectationResult
case class TestPassed() extends ExpectationResult
case class TestFailed(output: String) extends ExpectationResult
case class TestError(ex: Throwable) extends ExpectationResult

class CompoundExpectation(inner: Seq[Expectation]) given (ec: ExecutionContext) extends Expectation {
  def evaluate(): Future[ExpectationResult] = {
    val innerFutures = inner.map(_.evaluate())
    Future.sequence(innerFutures).map { results =>
      // any failure => failure
      ???
    }
  }
}

class AssertionError(msg: String) extends RuntimeException(msg)

class Expect[T](blk: => T, negated: Boolean = false) {
  def evaluate(): T = blk
  def isNegated: Boolean = negated
  def negate(): Expect[T] = new Expect(blk, !negated)
}

trait ExpectGivens {

  def (expect: Expect[T]) not[T]: Expect[T] = expect.negate()

  def (expect: Expect[T]) toEqual[T] (expected: T) given (eqq: Eq[T], fmt: Formatter[T]): Expectation = {
    new Expectation {
      def evaluate(): Future[ExpectationResult] = {
        val actual = expect.evaluate()

        var comparisonResult = eqq.areEqual(actual, expected)
        if (expect.isNegated) comparisonResult = !comparisonResult

        val r = if (!comparisonResult) {
          val actualStr = fmt.format(actual)
          val expectedStr = fmt.format(expected)

          val desc = if (expect.isNegated)
            s"Expected $actualStr to not equal $expectedStr"
          else
            s"Expected $expectedStr but found $actualStr"
          TestFailed(desc)
        } else TestPassed()
        Future.successful(r)
      }
    }
  }

  def (expect: Expect[Future[T]]) toCompleteWith[T] (expected: T) 
      given (
        eqq: Eq[T], 
        fmt: Formatter[T], 
        errFmt: Formatter[Throwable], 
        ec: ExecutionContext
      ): Expectation = {
    new Expectation {
      def evaluate(): Future[ExpectationResult] = {
        expect.evaluate().transform {
          case Success(actual) =>
            var comparisonResult = eqq.areEqual(actual, expected)
            if (expect.isNegated) comparisonResult = !comparisonResult

            val r = if (!comparisonResult) {
              val actualStr = fmt.format(actual)
              val expectedStr = fmt.format(expected)

              val desc = if (expect.isNegated)
                s"Expected Future not to be completed with $expectedStr"
              else
                s"Expected Future to be completed with $expectedStr but found $actualStr"
              TestFailed(desc)
            } else TestPassed()
            Success(r)
            // compare
          case Failure(_) if expect.isNegated =>
            // ok, Future was not completed with <expected>
            Success(TestPassed())
          case Failure(t) =>
            val expectedStr = fmt.format(expected)
            val errorStr = errFmt.format(t)
            val desc = s"Expected Future to be completed with $expectedStr but it failed with $errorStr"
            val r = TestFailed(desc)
            Success(r)
        }
      }
    }
  }

  def (expect: Expect[IterableOnce[T]]) toContain[T] (expected: T) 
      given (
        eqq: Eq[T],
        fmt: Formatter[T],
        ec: ExecutionContext
      ): Expectation = {
    new Expectation {
      def evaluate(): Future[ExpectationResult] = {
        val actual = expect.evaluate()

        val seen = ListBuffer[String]()
        var found = false
        val iterator = actual.iterator
        while (iterator.hasNext) {
          val next = iterator.next()
          seen += fmt.format(next)
          if (!found && eqq.areEqual(next, expected)) {
            found = true
          }
          // TODO: use some heuristic here. Should we continue to collect item string representations? For how long?
        }

        val allGood = if (expect.isNegated) !found else found

        val r = if (!allGood) {
          val actualStr = actual.getClass.getSimpleName + seen.mkString("(", ", ", ")")
          val expectedStr = fmt.format(expected)

          val desc = if (expect.isNegated)
            s"Expected $actualStr to not contain $expectedStr"
          else
            s"Expected $expectedStr to contain $actualStr"
          TestFailed(desc)
        } else TestPassed()
        Future.successful(r)
      }
    }
  }

  // TODO:
  // - toContain i lista (massa varianter, IterableOnce-ish)
  // - toContain i Map (immutable + mutable)
  // - toContainKey+toContainValue i Map
  // - i Jasmine: expect(x).toEqual(jasmine.objectContaining({ foo: jasmine.arrayContaining("bar") }))
  // - toContain(value | KeyConstraint | ValueConstraint)
  //     - expect(myMap).toContain(key("foo"))
  //     - expect(myMap) toContain "foo" -> 42
  //     - expect(myMap) toContain(value(42))
  //     - expect(myMap).to.contain.key(42)
}