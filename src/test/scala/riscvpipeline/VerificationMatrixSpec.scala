package riscvpipeline
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
class VerificationMatrixSpec extends AnyFlatSpec with Matchers {
  "Chapter8 endian matrix" should "include checked subword offsets and endian transitions for every configuration" in {
    for(v <- Chapter8TestPlanCases.variants) {
      val c = Chapter8TestPlanCases.all.filter(_.variant == v).find(_.id=="ENWIDTH")
      c.isDefined shouldBe true
      val events=c.get.expected
      Set(1,2,4,8,3,12,15).foreach(mask => events.exists(_.mask == BigInt(mask)) shouldBe true)
      events.last shouldBe Chapter8Programs.doneWrite
    }
  }
}
