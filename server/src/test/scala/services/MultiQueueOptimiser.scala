package services

import org.renjin.sexp._

import java.io.InputStream
import javax.script._

object MultiQueueOptimiser {

  // 1) Prepare your minute-by-minute inputs (length 1440)
  def runSingleQueue(q1Counts: IndexedSeq[Double],
                     q2Counts: IndexedSeq[Double],
                     desksPerMin: IndexedSeq[Double]) = {

    // 2) Start Renjin and load qsim.R + helpers
    val engine = new ScriptEngineManager().getEngineByName("Renjin")

    loadScript(engine, "/qqa.R")
    loadScript(engine, "/qsim.R")

    engine.eval(
      """
  build_job_df_rows <- function(jobtype, queue_name, outflow_name,
                                arrival_times, desk_txtimes) {
    n <- length(arrival_times)
    inflow_col  <- paste0(jobtype, ".inflow.route")
    route_col   <- "desk.route"
    txtime_col  <- "desk.txtime"
    df <- data.frame(
      arrival.time = as.numeric(arrival_times),
      dummy1 = factor(rep(queue_name, n),  levels=c(queue_name)),
      dummy2 = factor(rep(outflow_name, n),levels=c(outflow_name)),
      dummy3 = as.numeric(desk_txtimes),
      check.names = FALSE
    )
    names(df)[2:4] <- c(inflow_col, route_col, txtime_col)
    df[order(df$arrival.time), , drop=FALSE]
  }

  ms_desks_per_minute <- function(ms, horizon) {
    util <- ms$util
    minutes <- 0:(horizon-1)
    out <- integer(length(minutes))
    if (nrow(util) == 0) return(out)
    for (i in seq_len(nrow(util))) {
      from <- util$from[i]; till <- util$till[i]
      idx <- which(minutes >= from & minutes < till)
      if (length(idx)) out[idx] <- util$servers[i]
    }
    out
  }
  """
    )

    // --- 3) Hard-coded inputs ---
    // 10 arrivals (minute-of-day ticks)
    val arrivalsMin: Array[Int] = Array(0, 0, 1, 3, 5, 7, 10, 12, 20, 55)

    // Service times in *seconds* (paired to arrivals above)
    val serviceSec: Array[Double] = Array(45, 25, 72, 30, 90, 40, 60, 55, 20, 180)

    // Convert seconds -> minutes (because 1 tick = 1 minute here)
    val serviceTicks: Array[Double] = serviceSec.map(_ / 60.0)

    // Varying total desks over a 60-minute horizon:
    // 0-9: 2 desks, 10-29: 3 desks, 30-44: 1 desk, 45-59: 4 desks
    val horizon = 60
    val desksPerMin = Array.fill(horizon)(2.0)
    for (m <- 10 until 30) desksPerMin(m) = 3.0
    for (m <- 30 until 45) desksPerMin(m) = 1.0
    for (m <- 45 until 60) desksPerMin(m) = 4.0

    // --- 4) Ship data to R and assemble the db (single queue/jobtype: q1) ---
    engine.put("q1_arrival_times", arrivalsMin)
    engine.put("q1_desk_txtimes", serviceTicks)

    engine.eval(
      """
  q1_df <- build_job_df_rows("q1", "q1.queue", "q1.outflow",
                             q1_arrival_times, q1_desk_txtimes)
  db <- list(q1 = q1_df)
  """
    )

    // --- 5) Build sim: one queue, one multiserver "desk.q1", one shared resource "staff" ---
    engine.put("H", horizon)
    engine.put("desks_per_min", desksPerMin)

    engine.eval(
      """
  # Create simulation
  sim <- new.simulation(db)

  # Queue and server
  make.queue(sim, "q1.queue", main.server.name="desk.q1", job.type="q1", sla=20)
  make.multiserver(sim, "desk.q1", server.type="desk", capacity=0, job.type="q1",
                   queue.names.served=c("q1.queue"))

  # Resource that controls the multiserver capacity (1 desk -> 1 server)
  staff <- make.resource(sim, "staff", initial.quantity=0,
                         used.by=c("desk.q1"),
                         resource2capacity=list(desk=function(x) x),
                         capacity2resource=list(desk=function(x) x))

  # Per-minute schedule of total desks available
  make.scheduler(sim, "staff", times = 0:(H-1), quantities = desks_per_min, overwrite=TRUE)

  # Allocate by minimising wait (calls resource$allocate.min.wait via marshal)
  sim$marshal$set.allocation.method(method="wait", new.interval=1)

  # Run
  sim$run()

  # Per-minute desks actually open on desk.q1
  desks_q1 <- ms_desks_per_minute(sim$`desk.q1`, horizon = H)

  # Simple queue metrics
  q1_mean <- sim$`q1.queue`$qmean(TRUE)
  q1_p95  <- sim$`q1.queue`$qquantiles(0.95, TRUE)
  """
    )

    // --- 6) Read results back into Scala ---
    // Desks per minute (length = 60)
    val desksQ1Vec = engine.eval("desks_q1").asInstanceOf[DoubleArrayVector]
    val desksQ1 = Array.tabulate(horizon)(i => desksQ1Vec.getElementAsDouble(i))

    // Queue metrics
    val q1Mean = engine.eval("q1_mean").asInstanceOf[DoubleArrayVector].getElementAsDouble(0)
    val q1P95 = engine.eval("q1_p95").asInstanceOf[DoubleArrayVector].getElementAsDouble(0)

    // --- 7) Print a small report ---
    println("Minute : Desks open")
    for (m <- 0 until horizon) {
      println(f"$m%02d : ${desksQ1(m)}%.0f")
    }
    println(f"\nQ1 mean wait: $q1Mean%.3f ticks (minutes)")
    println(f"Q1 95th pct:  $q1P95%.3f ticks (minutes)")
  }

  private def loadScript(engine: ScriptEngine, fileName: String) = {
    val asStream: InputStream = getClass.getResourceAsStream(fileName)

    val script = scala.io.Source.fromInputStream(asStream).bufferedReader()

    engine.eval(script)
  }

  // 1) Prepare your minute-by-minute inputs (length 1440)
  def runTwoQueues(q1Counts: IndexedSeq[Double],
                   q2Counts: IndexedSeq[Double],
                   desksPerMin: IndexedSeq[Double]) = {

    // 2) Start Renjin and load qsim.R + helpers
    val engine = new ScriptEngineManager().getEngineByName("Renjin")

    loadScript(engine, "/qqa.R")
    loadScript(engine, "/qsim.R")

    engine.eval(
      """
  build_job_df_rows <- function(jobtype, queue_name, outflow_name,
                                arrival_times, desk_txtimes) {
    n <- length(arrival_times)
    inflow_col  <- paste0(jobtype, ".inflow.route")
    route_col   <- "desk.route"
    txtime_col  <- "desk.txtime"
    df <- data.frame(
      arrival.time = as.numeric(arrival_times),
      dummy1 = factor(rep(queue_name, n),  levels=c(queue_name)),
      dummy2 = factor(rep(outflow_name, n),levels=c(outflow_name)),
      dummy3 = as.numeric(desk_txtimes),
      check.names = FALSE
    )
    names(df)[2:4] <- c(inflow_col, route_col, txtime_col)
    df[order(df$arrival.time), , drop=FALSE]
  }

  ms_desks_per_minute <- function(ms, horizon) {
    util <- ms$util
    minutes <- 0:(horizon-1)
    out <- integer(length(minutes))
    if (nrow(util) == 0) return(out)
    for (i in seq_len(nrow(util))) {
      from <- util$from[i]; till <- util$till[i]
      idx <- which(minutes >= from & minutes < till)
      if (length(idx)) out[idx] <- util$servers[i]
    }
    out
  }
  """
    )

    // --- 3) Hard-coded inputs (ticks = minutes) ---
    val horizon = 60

    // Queue 1: 10 passengers
    val q1ArrivalsMin = Array(0, 0, 1, 3, 5, 7, 10, 12, 20, 55) // arrival ticks (minutes)
    val q1ServiceSec = Array(45, 25, 72, 30, 90, 40, 60, 55, 20, 180) // seconds
    val q1ServiceTicks = q1ServiceSec.map(_ / 60.0) // convert to minutes

    // Queue 2: its own 10 passengers
    val q2ArrivalsMin = Array(0, 2, 2, 4, 6, 9, 11, 15, 22, 50)
    val q2ServiceSec = Array(30, 80, 35, 50, 65, 45, 70, 40, 25, 120)
    val q2ServiceTicks = q2ServiceSec.map(_ / 60.0)

    // Varying total desks for the shared pool over 60 minutes
    // 0-9: 2 desks, 10-29: 3 desks, 30-44: 1 desk, 45-59: 4 desks
    val desksPerMin = Array.fill(horizon)(2.0)
    for (m <- 10 until 30) desksPerMin(m) = 3.0
    for (m <- 30 until 45) desksPerMin(m) = 1.0
    for (m <- 45 until 60) desksPerMin(m) = 4.0

    // --- 4) Ship data to R & assemble the db for q1 and q2 ---
    engine.put("q1_arrival_times", q1ArrivalsMin)
    engine.put("q1_desk_txtimes", q1ServiceTicks)
    engine.put("q2_arrival_times", q2ArrivalsMin)
    engine.put("q2_desk_txtimes", q2ServiceTicks)

    engine.eval(
      """q1_df <- build_job_df_rows("q1", "q1.queue", "q1.outflow",
        |     q1_arrival_times, q1_desk_txtimes)
        |q2_df <- build_job_df_rows("q2", "q2.queue", "q2.outflow",
        |     q2_arrival_times, q2_desk_txtimes)
        |db <- list(q1 = q1_df, q2 = q2_df)
        |""".stripMargin
    )

    // --- 5) Build sim: one queue, one multiserver "desk.q1", one shared resource "staff" ---
    engine.put("H", horizon)
    engine.put("desks_per_min", desksPerMin)

    engine.eval("sim <- new.simulation(db)\n")
    engine.eval("""make.queue(sim, "q1.queue", main.server.name="desk.q1", job.type="q1", sla=20)""")
    engine.eval("""make.queue(sim, "q2.queue", main.server.name="desk.q2", job.type="q2", sla=30)""")
    engine.eval("""make.multiserver(sim, "desk.q1", server.type="desk", capacity=0, job.type="q1",
                   queue.names.served=c("q1.queue"), max.capacity=Inf)""".stripMargin)
    engine.eval("""make.multiserver(sim, "desk.q2", server.type="desk", capacity=0, job.type="q2",
                   queue.names.served=c("q2.queue"), max.capacity=Inf)""".stripMargin)

    engine.eval(
      """# Shared resource (pool of desks), used by BOTH multiservers
        |  staff <- make.resource(sim, "staff", initial.quantity=0,
        |                         used.by=c("desk.q1","desk.q2"),
        |                         resource2capacity=list(desk=function(x) x),
        |                         capacity2resource=list(desk=function(x) x))
        """.stripMargin)

    engine.eval(
      """# Per-minute schedule of total desks available
        |  make.scheduler(sim, "staff", times = 0:(H-1), quantities = desks_per_min, overwrite=TRUE)
        |""".stripMargin)

    engine.eval(
      """# Allocation strategy: minimise total wait (calls resource$allocate.min.wait on polls)
        |  sim$marshal$set.allocation.method(method="wait", new.interval=1)
        |""".stripMargin)
    engine.eval(
      """# Run the simulation
        |  sim$run()
        |""".stripMargin)
    engine.eval(
      """# Per-minute desks actually open on each multiserver
        |  desks_q1 <- ms_desks_per_minute(sim$`desk.q1`, horizon = H)
        |""".stripMargin)
    engine.eval(
      """# Per-minute desks actually open on each multiserver
        |  desks_q2 <- ms_desks_per_minute(sim$`desk.q2`, horizon = H)
        |""".stripMargin)

    engine.eval(
      """# A couple of queue metrics
        |  q1_mean <- sim$`q1.queue`$qmean(TRUE); q1_p95 <- sim$`q1.queue`$qquantiles(0.95, TRUE)
        |""".stripMargin)
    engine.eval(
      """# A couple of queue metrics
        |q2_mean <- sim$`q2.queue`$qmean(TRUE); q2_p95 <- sim$`q2.queue`$qquantiles(0.95, TRUE)
        |""".stripMargin)

    // --- 6) Fetch results back into Scala ---
    def pullVec(name: String, n: Int): Array[Double] = {
      val v = engine.eval(name).asInstanceOf[DoubleArrayVector]
      Array.tabulate(n)(i => v.getElementAsDouble(i))
    }

    val desksQ1 = pullVec("desks_q1", horizon)
    val desksQ2 = pullVec("desks_q2", horizon)

    val q1Mean = engine.eval("q1_mean").asInstanceOf[DoubleArrayVector].getElementAsDouble(0)
    val q1P95 = engine.eval("q1_p95").asInstanceOf[DoubleArrayVector].getElementAsDouble(0)
    val q2Mean = engine.eval("q2_mean").asInstanceOf[DoubleArrayVector].getElementAsDouble(0)
    val q2P95 = engine.eval("q2_p95").asInstanceOf[DoubleArrayVector].getElementAsDouble(0)

    // --- 7) Print a compact report ---
    println("Minute : desks_q1  desks_q2  (total desks scheduled)")
    for (m <- 0 until horizon) {
      println(f"$m%02d     ${desksQ1(m)}%.0f        ${desksQ2(m)}%.0f          ${desksPerMin(m)}%.0f")
    }
    println(f"\nQ1 mean wait: $q1Mean%.3f ticks; 95th: $q1P95%.3f")
    println(f"Q2 mean wait: $q2Mean%.3f ticks; 95th: $q2P95%.3f")
  }

}
