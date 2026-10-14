package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VerificationObserverSpec extends AnyFlatSpec with riscvpipeline.ProjectTest with Matchers {
  "Verification observer" should "align M memory events with W results and cancel wrong path instructions" in {
    import PipelineTestSupport._
    val words = Seq(addi(1,0,40), addi(2,1,2), store(2,0), load(3,0,0),
      addi(4,3,1), jal(0,8), store(4,4), store(4,8), jal(0,0))
    test(new VerificationObserver(image(words))) { dut =>
      for (_ <- 0 until 2) {
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        val records = scala.collection.mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt,BigInt)]
        for (_ <- 0 until 35) {
          if(dut.commitValid.peek().litToBoolean) {
            val pc = dut.commitPC.peek().litValue
            if(pc == 8 || pc == 28) {
              dut.commitRW.expect(1.U); dut.commitMask.expect(15.U)
              dut.commitAddress.expect((if(pc==8) 0 else 8).U)
              dut.commitData.expect((if(pc==8) 42 else 43).U)
            }
            if(pc == 12) { dut.commitRW.expect(2.U); dut.commitAddress.expect(0.U) }
            records += ((dut.commitPC.peek().litValue,dut.commitInstr.peek().litValue,
              dut.commitRd.peek().litValue,dut.commitValue.peek().litValue))
          }
          dut.clock.step()
        }
        records.take(8).map(_._1).toSeq shouldBe Seq(0,4,8,12,16,20,28,32).map(BigInt(_))
        records.find(_._1==12).get._4 shouldBe BigInt(42)
        records.find(_._1==16).get._4 shouldBe BigInt(43)
      }
    }
  }
}
