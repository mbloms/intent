package intent

import java.net.URLClassLoader
import scala.util.control.NonFatal

/**
 * Seems to be required in order to use the SBT test-class fingerprinting.
 * Could not get trait alone to work...
 */
class IntentMaker {}

trait ITestCase {
  def nameParts: Seq[String]
  def run(): Unit
}

trait Intent[TState] extends FormatterGivens with EqGivens {
  type Transform = TState => TState
  case class SetupPart(name: String, transform: Transform)
  case class TransformAndBlock(transform: Transform, blk: () => Unit)
  case class TestCase(setup: Seq[SetupPart], name: String, impl: TState => Unit) extends ITestCase {
    def nameParts: Seq[String] = setup.map(_.name)
    def run(): Unit = {
      val state = setup.foldLeft(emptyState)((st, part) => part.transform(st))
      impl(state)
    }
  }

  def allTestCases: Seq[ITestCase] = testCases

  private var testCases: Seq[TestCase] = Seq.empty
  private var reverseSetupStack: Seq[SetupPart] = Seq.empty

  // behövs för att extension-metoder appliceras höger till vänster??
  def (blockName: String) via (transformAndBlock: TransformAndBlock): Unit = {
    SetupPart(blockName, transformAndBlock.transform) - transformAndBlock.blk()
  }

  def (setupPart: SetupPart) - (block: => Unit): Unit = {
    reverseSetupStack +:= setupPart
    try block finally reverseSetupStack = reverseSetupStack.tail
  }

  def (blockName: String) - (block: => Unit): Unit = {
    val setupPart = SetupPart(blockName, s => s)
    reverseSetupStack +:= setupPart
    try block finally reverseSetupStack = reverseSetupStack.tail
  }

  // hack för att "XX via YY - BLK" evalueras som "XX via (YY - BLK)"
  def (transform: Transform) - (block: => Unit): TransformAndBlock = {
    TransformAndBlock(transform, () => block)
  }

  def (testName: String) in (testImpl: TState => Unit): Unit = {
    val parts = reverseSetupStack.reverse
    testCases :+= TestCase(parts, testName, testImpl)
  }

  def expect[T](expr: => T): Expect[T] = new Expect[T](expr)

  def emptyState: TState
}

class AssertionError(msg: String) extends RuntimeException(msg)

class Expect[T](blk: => T) {
  def toEqual(other: T) given (eq: Eq[T], fmt: Formatter[T]): Unit = {
    val value = blk
    if (!eq.areEqual(value, other)) {
      val actualStr = fmt.format(value)
      val expectedStr = fmt.format(other)
      throw new AssertionError(s"Expected $expectedStr but got $actualStr")
    }
  }
}
