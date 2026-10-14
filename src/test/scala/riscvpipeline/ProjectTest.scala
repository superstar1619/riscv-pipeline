package riscvpipeline

import chisel3.Module
import chiseltest.ChiselScalatestTester
import firrtl.options.TargetDirAnnotation

/** Keep default simulator work directories inside the disposable build tree. */
trait ProjectTest extends ChiselScalatestTester {
  this: org.scalatest.TestSuite =>
  override def test[T <: Module](dutGen: => T): TestBuilder[T] = {
    val name = getTestName.replaceAll("[^A-Za-z0-9_-]", "_")
    super.test(dutGen).withAnnotations(Seq(TargetDirAnnotation(s"target/test-runs/$name")))
  }
}
