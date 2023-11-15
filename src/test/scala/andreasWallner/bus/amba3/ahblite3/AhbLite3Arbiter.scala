package andreasWallner.bus.amba3.ahblite3

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.ahblite.{AhbLite3, AhbLite3Config}

case class AhbLite3Control(config: AhbLite3Config) extends Bundle with IMasterSlave {
  val HADDR = UInt(config.addressWidth bits)
  val HSEL = Bool()
  val HREADY = Bool()
  val HWRITE = Bool()
  val HSIZE = Bits(3 bit)
  val HBURST = Bits(3 bit)
  val HPROT = Bits(4 bit)
  val HTRANS = Bits(2 bit)
  val HMASTLOCK = Bool()

  override def asMaster(): Unit = {
    out(HADDR, HSEL, HWRITE, HSIZE, HBURST, HPROT, HTRANS, HMASTLOCK)
  }

  def >>(ahb: AhbLite3): Unit = {
    ahb.HADDR := HADDR
    ahb.HSEL := HSEL
    ahb.HWRITE := HWRITE
    ahb.HSIZE := HSIZE
    ahb.HBURST := HBURST
    ahb.HPROT := HPROT
    ahb.HTRANS := HTRANS
    ahb.HMASTLOCK := HMASTLOCK
  }
}
object AhbLite3Control {
  def apply(ahb: AhbLite3): AhbLite3Control = {
    val control = AhbLite3Control(ahb.config)
    control.HADDR := ahb.HADDR
    control.HSEL := ahb.HSEL
    control.HWRITE := ahb.HWRITE
    control.HSIZE := ahb.HSIZE
    control.HBURST := ahb.HBURST
    control.HPROT := ahb.HPROT
    control.HTRANS := ahb.HTRANS
    control.HMASTLOCK := ahb.HMASTLOCK
    control
  }

  def idleControl(config: AhbLite3Config) = {
    val control = AhbLite3Control(config)
    control.HADDR.assignDontCare()
    control.HSEL := False
    control.HWRITE.assignDontCare()
    control.HSIZE.assignDontCare()
    control.HBURST := B"000"
    control.HPROT := B"0000"
    control.HTRANS := B"00"
    control.HMASTLOCK := False
    control
  }
}

/**
  * AHB Lite arbiter
  *
  * @param ahbLite3Config : Ahb bus configuration
  * @param inputsCount    : Number of inputs for the arbiter
  */
case class AhbLite3Arbiter(
    ahbLite3Config: AhbLite3Config,
    inputsCount: Int,
    roundRobinArbiter: Boolean = true
) extends Component {
  val io = new Bundle {
    val inputs = Vec(slave(AhbLite3(ahbLite3Config)), inputsCount)
    val output = master(AhbLite3(ahbLite3Config))
  }

  val logic = if (inputsCount == 1) new Area {
    io.output << io.inputs.head
  }
  else
    new Area {
      val hold = Vec(Bool(), inputsCount)
      val locked = RegInit(False)
      val slaveAdvancing = Bool()

      val bufferedCtrl = Vec.tabulate(inputsCount) { i =>
        SkidBuffer(
          AhbLite3Control(io.inputs(i)),
          hold(i),
          AhbLite3Control.idleControl(ahbLite3Config)
        )
      }

      val dataPhaseActive = Reg(Bool())
        .clearWhen(io.output.HREADYOUT)
        .setWhen(io.output.HSEL && io.output.HREADY)
        .init(False)
      slaveAdvancing :=
        ((!dataPhaseActive && io.output.HSEL.rise(initAt = False)) ||
          (dataPhaseActive && io.output.HREADYOUT))// && !locked

      val maskProposal = Bits(inputsCount bits)
      val maskArbitrated = RegNextWhen(maskProposal, slaveAdvancing, init = B(0))
      val maskLocked = locked.mux(maskArbitrated, maskProposal)

      val lastArea = new Area {
        val beatCounter = Reg(UInt(4 bits)) init (0)
        val isLast = Vec(U"0000", U"0011", U"0111", U"1111")(U(io.output.HBURST >> 1)) === beatCounter || (io.output.HREADY && io.output.ERROR)

        when(io.output.HSEL && io.output.HREADY && io.output.HTRANS(1)) {
          beatCounter := beatCounter + 1
          when(isLast) {
            beatCounter := 0
          }
        }
      }

      when(io.output.HSEL) { //valid
        locked := io.output.HBURST =/= 0 || io.output.HMASTLOCK

        when(io.output.HREADY) { //fire
          when(lastArea.isLast && !io.output.HMASTLOCK) { //End of burst and no lock
            locked := False
          }
        }
      }

      val requests = bufferedCtrl.map(i => i.HSEL && i.HTRANS =/= 0).asBits // which master requests, masterReq
      // next master being selected
      maskProposal := (if (roundRobinArbiter)
                         OHMasking.roundRobin(
                           requests,
                           maskArbitrated(maskArbitrated.high - 1 downto 0) ## maskArbitrated.msb
                         )
                       else
                         OHMasking.first(requests))
      val requestsDel = RegNext(requests) init 0

      hold.zipWithIndex.foreach { case (b, idx) => b := requestsDel(idx) && ~maskArbitrated(idx) }

      val requestIndex = OHToUInt(maskLocked)
      val xsel = bufferedCtrl.map(_.HSEL).asBits()
      val xarb = maskLocked
      val selAndArb = xsel & xarb
      io.output.HSEL := bufferedCtrl(requestIndex).HSEL
      io.output.HADDR := bufferedCtrl(requestIndex).HADDR
      io.output.HWRITE := bufferedCtrl(requestIndex).HWRITE
      io.output.HSIZE := bufferedCtrl(requestIndex).HSIZE
      io.output.HBURST := bufferedCtrl(requestIndex).HBURST
      io.output.HPROT := bufferedCtrl(requestIndex).HPROT
      io.output.HTRANS := io.output.HSEL ? bufferedCtrl(requestIndex).HTRANS | B"00"
      io.output.HMASTLOCK := bufferedCtrl(requestIndex).HMASTLOCK

      // Provide HREADY if the slave is not active, the specifiction does not require
      // specific behaviour in this case. If we don't do this a slave that generates a
      // low HREADYOUT would deadlock itself.
      io.output.HREADY := io.output.HREADYOUT || !dataPhaseActive

      val dataIndex = RegNextWhen(requestIndex, slaveAdvancing)
      io.output.HWDATA := io.inputs(dataIndex).HWDATA

      for (((buffered, input, requestRouted), idx) <- (
             bufferedCtrl,
             io.inputs,
             maskArbitrated.asBools
           ).zipped.zipWithIndex) {
        val trans_d = RegNextWhen(buffered.HTRANS, slaveAdvancing) init 0
        val sel_d = RegNext(buffered.HSEL, slaveAdvancing) init True
        val stall = hold(idx) & !maskArbitrated(idx)
        val idle_resp = RegNext(buffered.HTRANS === 0 && buffered.HSEL && !hold(idx)) init True
        idle_resp.setName("idle_resp" + input.getName())
        input.HRDATA := io.output.HRDATA
        input.HRESP := idle_resp ? False | io.output.HRESP
        input.HREADYOUT := !stall && ((!requestRouted && !buffered.HSEL) || (requestRouted && io.output.HREADYOUT) || (trans_d === 0 && sel_d))
      }

    }
}
