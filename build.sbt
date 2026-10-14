name := "riscv-pipeline"
version := "0.1.0-SNAPSHOT"
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

scalaVersion := "2.13.14"
val chiselVersion = "3.6.1"
addCompilerPlugin(
  "edu.berkeley.cs" %% "chisel3-plugin" % chiselVersion cross CrossVersion.full
)
libraryDependencies += "edu.berkeley.cs" %% "chisel3" % chiselVersion
libraryDependencies += "edu.berkeley.cs" %% "chiseltest" % "0.6.2" % Test

Compile / run / mainClass := Some("riscvpipeline.generators.GenerateRiscvPipeline")

// These images are generated inputs, so a clean checkout can compile and run tests.
lazy val buildAcceptanceImages = taskKey[Unit]("Build independent RV32I acceptance inputs")
buildAcceptanceImages := {
  val status = scala.sys.process.Process(
    Seq("python3", "scripts/build/build-rv32-acceptance.py"), baseDirectory.value
  ).!
  if (status != 0) sys.error("RV32I acceptance image build failed")
}
Test / compile := (Test / compile).dependsOn(buildAcceptanceImages).value
