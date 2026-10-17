package riscvpipeline.config

/** 第九章 34 位物理地址；容量单位为字节，额外等待单位为周期。 */
final case class BusConfig(
  bootRomBase: BigInt = 0x1000,
  bootRomBytes: Int = 4096,
  bootRomInitFile: Option[String] = None,
  ramBase: BigInt = BigInt("80000000", 16),
  ramBytes: Int = 65536,
  apbBase: BigInt = 0x10000000,
  apbBytes: Int = 4096,
  ramLatency: Int = 0,
  romLatency: Int = 0,
  apbLatency: Int = 0,
  iromSupported: Boolean = false,
  dtimSupported: Boolean = false
) {
  private val windows = Seq((bootRomBase, bootRomBytes), (ramBase, ramBytes),
    (apbBase, apbBytes))
  windows.foreach { case (base, bytes) =>
    require(bytes >= 4 && (bytes & (bytes - 1)) == 0, "window size must be a power of two >=4")
    require(base >= 0 && base % bytes == 0, "window base must be aligned")
    require(base + bytes <= (BigInt(1) << 34), "window must fit 34-bit physical address")
  }
  for (i <- windows.indices; j <- 0 until i) {
    val (a, as) = windows(i)
    val (b, bs) = windows(j)
    require(a + as <= b || b + bs <= a, "bus windows must not overlap")
  }
  Seq(ramLatency, romLatency, apbLatency).foreach { latency =>
    require(latency >= 0 && latency <= 255, "extra latency must be in 0..255")
  }
}
