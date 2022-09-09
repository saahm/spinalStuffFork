package andreasWallner.la

import spinal.core._
import spinal.sim._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite

case class MemoryFormatterTester(in_width: Int, out_width: Int)
    extends Component {
  val io = new Bundle {
    val i = slave(Stream(Bits(in_width bits)))
    val o = master(Stream(Bits(out_width bits)))
  }
  MemoryFormatter(io.i, io.o, 8)
}

class MemoryFormatterTest extends AnyFunSuite {
  for (inputWidth <- List(16, 24); outputWidth <- List(32, 64, 128)) {
    val dut = SimConfig.withFstWave
      .compile(
        MemoryFormatterTester(inputWidth, outputWidth)
          .setDefinitionName(
            f"MemoryFormatterTester_${inputWidth}_$outputWidth"
          )
      )

    test(f"MemoryFormatter $inputWidth-$outputWidth randomized") {
      dut.doSim(f"MemoryFormatter $inputWidth-$outputWidth randomized") { dut =>
        SimTimeout(100000)
        val scoreboard = ScoreboardInOrder[String]()
        StreamDriver(dut.io.i, dut.clockDomain) { payload =>
          payload.randomize()
          true
        }
        StreamMonitor(dut.io.i, dut.clockDomain) { payload =>
          //println(f"IN: ${payload.toInt}%06x")
          payload.toBigInt
            .hexString(inputWidth)
            .grouped(2)
            .foreach(scoreboard.pushRef)
        }

        StreamReadyRandomizer(dut.io.o, dut.clockDomain)
        StreamMonitor(dut.io.o, dut.clockDomain) { payload =>
          //println(f"OUT: ${payload.toBigInt}%016x")
          payload.toBigInt
            .hexString(outputWidth)
            .grouped(2)
            .foreach(scoreboard.pushDut)
        }
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitActiveEdgeWhere(scoreboard.matches >= 1000)
      }
    }
    test(f"MemoryFormatter $inputWidth-$outputWidth input pressure") {
      dut.doSim(f"MemoryFormatter $inputWidth-$outputWidth input pressure") {
        dut =>
          SimTimeout(100000)
          val scoreboard = ScoreboardInOrder[String]()
          StreamDriver(dut.io.i, dut.clockDomain) { payload =>
            payload.randomize()
            true
          }.transactionDelay = () => 0
          StreamMonitor(dut.io.i, dut.clockDomain) { payload =>
            //println(f"IN: ${payload.toInt}%06x")
            payload.toBigInt
              .hexString(inputWidth)
              .grouped(2)
              .foreach(scoreboard.pushRef)
          }

          StreamReadyRandomizer(dut.io.o, dut.clockDomain)
          StreamMonitor(dut.io.o, dut.clockDomain) { payload =>
            //println(f"OUT: ${payload.toBigInt}%016x")
            payload.toBigInt
              .hexString(outputWidth)
              .grouped(2)
              .foreach(scoreboard.pushDut)
          }
          dut.clockDomain.forkStimulus(10)
          dut.clockDomain.waitActiveEdgeWhere(scoreboard.matches >= 1000)
      }
    }
    test(f"MemoryFormatter $inputWidth-$outputWidth free output") {
      dut.doSim(f"MemoryFormatter $inputWidth-$outputWidth free output") {
        dut =>
          SimTimeout(100000)
          val scoreboard = ScoreboardInOrder[String]()
          StreamDriver(dut.io.i, dut.clockDomain) { payload =>
            payload.randomize()
            true
          }
          StreamMonitor(dut.io.i, dut.clockDomain) { payload =>
            //println(f"IN: ${payload.toInt}%06x")
            payload.toBigInt
              .hexString(inputWidth)
              .grouped(2)
              .foreach(scoreboard.pushRef)
          }

          dut.io.o.ready #= true
          StreamMonitor(dut.io.o, dut.clockDomain) { payload =>
            //println(f"OUT: ${payload.toBigInt}%016x")
            payload.toBigInt
              .hexString(outputWidth)
              .grouped(2)
              .foreach(scoreboard.pushDut)
          }
          dut.clockDomain.forkStimulus(10)
          dut.clockDomain.waitActiveEdgeWhere(scoreboard.matches >= 1000)
      }
    }
    test(f"MemoryFormatter $inputWidth-$outputWidth max flow") {
      dut.doSim(f"MemoryFormatter $inputWidth-$outputWidth max flow") { dut =>
        SimTimeout(100000)
        val scoreboard = ScoreboardInOrder[String]()
        StreamDriver(dut.io.i, dut.clockDomain) { payload =>
          payload.randomize()
          true
        }.transactionDelay = () => 0
        StreamMonitor(dut.io.i, dut.clockDomain) { payload =>
          //println(f"IN: ${payload.toInt}%06x")
          payload.toBigInt
            .hexString(inputWidth)
            .grouped(2)
            .foreach(scoreboard.pushRef)
        }

        dut.io.o.ready #= true
        StreamMonitor(dut.io.o, dut.clockDomain) { payload =>
          //println(f"OUT: ${payload.toBigInt}%016x")
          payload.toBigInt
            .hexString(outputWidth)
            .grouped(2)
            .foreach(scoreboard.pushDut)
        }
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitActiveEdgeWhere(scoreboard.matches >= 1000)
      }
    }
  }
}
