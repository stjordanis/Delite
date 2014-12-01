package ppl.delite.runtime

import org.apache.mesos._
import org.apache.mesos.Protos._
import com.google.protobuf.ByteString
import java.util.HashMap
import java.util.concurrent.locks.ReentrantLock
import ppl.delite.runtime.data.RemoteDeliteArray
import ppl.delite.runtime.graph.{Stencil,Empty,All,Const,One,Interval,KnownInterval}
import ppl.delite.runtime.messages.Messages._
import ppl.delite.runtime.messages._


class DeliteMesosScheduler(private val executor: ExecutorInfo) extends Scheduler {

  private var numSlavesReturned: Int = 0

  /**
   * Invoked when the scheduler successfully registers with a Mesos
   * master. A unique ID (generated by the master) used for
   * distinguishing this framework from others and MasterInfo
   * with the ip and port of the current master are provided as arguments.
   */
  def registered(driver: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo) {
    println("Delite Scheduler registered with master " + masterInfo.getId)
  }

  /**
   * Invoked when the scheduler re-registers with a newly elected Mesos master.
   * This is only called when the scheduler has previously been registered.
   * MasterInfo containing the updated information about the elected master
   * is provided as an argument.
   */
  def reregistered(driver: SchedulerDriver, masterInfo: MasterInfo) {
    println("Delite Scheduler registered with new master " + masterInfo.getId)
  }

  /**
   * Invoked when resources have been offered to this framework. A
   * single offer will only contain resources from a single slave.
   * Resources associated with an offer will not be re-offered to
   * _this_ framework until either (a) this framework has rejected
   * those resources (see {@link SchedulerDriver#launchTasks}) or (b)
   * those resources have been rescinded (see {@link offerRescinded}).
   * Note that resources may be concurrently offered to more than one
   * framework at a time (depending on the allocator being used). In
   * that case, the first framework to launch tasks using those
   * resources will be able to use them while the other frameworks
   * will have those resources rescinded (or if a framework has
   * already launched tasks with those resources then those tasks will
   * fail with a TASK_LOST status and a message saying as much).
   */
  def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    if (DeliteMesosScheduler.slaves != Nil)
      return

    var slaves = List[String]()
    var idx = 0
    var offersIter = offers.iterator
    while (offersIter.hasNext && idx < Config.numSlaves) {
      val offer = offersIter.next
      idx += 1
      slaves ::= offer.getHostname
      DeliteMesosScheduler.slaves ::= offer.getSlaveId

      println("slave offered @ " + offer.getHostname)
      for (i <- 0 until offer.getResourcesCount) {
        println("  " + offer.getResources(i).getName + " : " + offer.getResources(i).getScalar.getValue)
      }
    }

    DeliteMesosScheduler.slaves = DeliteMesosScheduler.slaves.reverse

    offersIter = offers.iterator
    idx = 0
    while (offersIter.hasNext && idx < Config.numSlaves) {
      val offer = offersIter.next

      val taskId = "Delite Runtime " + offer.getSlaveId.getValue
      val cpus = offer.getResources(0)
      val mem = offer.getResources(1)

      val mssg = LaunchInfo.newBuilder
        .setMasterAddress(DeliteMesosScheduler.network.id.host)
        .setMasterPort(DeliteMesosScheduler.network.id.port)
        .setSlaveIdx(idx)

      for (arg <- DeliteMesosScheduler.appArgs) mssg.addArg(arg)
      idx += 1

      val task = TaskInfo.newBuilder
        .setName(taskId)
        .setTaskId(TaskID.newBuilder.setValue(taskId))
        .setSlaveId(offer.getSlaveId)
        .addResources(cpus)
        .addResources(mem)
        .setExecutor(executor)
        .setData(mssg.build.toByteString)
        .build

      val tasks = new java.util.LinkedList[TaskInfo]
      tasks.add(task)
      driver.launchTasks(offer.getId, tasks)
    }
  }

  /**
   * Invoked when an offer is no longer valid (e.g., the slave was
   * lost or another framework used resources in the offer). If for
   * whatever reason an offer is never rescinded (e.g., dropped
   * message, failing over framework, etc.), a framwork that attempts
   * to launch tasks using an invalid offer will receive TASK_LOST
   * status updates for those tasks (see {@link #resourceOffers}).
   */
  def offerRescinded(driver: SchedulerDriver, offerId: OfferID) {
    println("ERROR: offer rescinded not handled")
  }

  /**
   * Invoked when the status of a task has changed (e.g., a slave is
   * lost and so the task is lost, a task finishes and an executor
   * sends a status update saying so, etc). Note that returning from
   * this callback _acknowledges_ receipt of this status update! If
   * for whatever reason the scheduler aborts during this callback (or
   * the process exits) another status update will be delivered (note,
   * however, that this is currently not true if the slave sending the
   * status update is lost/fails during that time).
   */
  def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    def abnormalShutdown(reason: Exception) {
      DeliteMesosScheduler.network.stop()
      driver.stop()
      Delite.shutdown(reason)
    }

    status.getState match {
      case TaskState.TASK_RUNNING =>
        DeliteMesosScheduler.addSlaveToNetwork(CommInfo.parseFrom(status.getData))
      case TaskState.TASK_LOST =>
        abnormalShutdown(new RuntimeException("slave task lost... unable to recover"))
      case TaskState.TASK_FAILED =>
        abnormalShutdown(new RuntimeException("task failed"))
    }
  }

  /**
   * Invoked when an executor sends a message. These messages are best
   * effort; do not expect a framework message to be retransmitted in
   * any reliable fashion.
   */
  def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]) {
    val mssg = DeliteSlaveMessage.parseFrom(data)
    mssg.getType match {
      case DeliteSlaveMessage.Type.RESULT => setResult(mssg.getResult)
      case DeliteSlaveMessage.Type.DEBUG => DeliteMesosScheduler.logFromSlave(DeliteMesosScheduler.slaves.indexOf(slaveId), mssg.getDebug.getMessage)
      case DeliteSlaveMessage.Type.WARNING => DeliteMesosScheduler.warnFromSlave(DeliteMesosScheduler.slaves.indexOf(slaveId), mssg.getDebug.getMessage)
    }

    def setResult(res: ReturnResult) = {
      DeliteMesosScheduler.remoteLock.lock()
      try {
        DeliteMesosScheduler.remoteResult(DeliteMesosScheduler.slaves.indexOf(slaveId)) = res
        numSlavesReturned += 1
        if (numSlavesReturned == DeliteMesosScheduler.activeSlaves) {
          numSlavesReturned = 0
          DeliteMesosScheduler.notCompleted = false
          DeliteMesosScheduler.remoteCompleted.signal()
        }
      }
      finally {
        DeliteMesosScheduler.remoteLock.unlock()
      }
    }
  }

  /**
   * Invoked when the scheduler becomes "disconnected" from the master
   * (e.g., the master fails and another is taking over).
   */
  def disconnected(driver: SchedulerDriver) {
    println("WARNING: Mesos master has disconnected... waiting for re-register with new master")
  }

  /**
   * Invoked when a slave has been determined unreachable (e.g.,
   * machine failure, network partition). Most frameworks will need to
   * reschedule any tasks launched on this slave on a new slave.
   */
  def slaveLost(driver: SchedulerDriver, slaveId: SlaveID) {
    println("ERROR: slave lost and fault tolerance not yet implemented")
  }

  /**
   * Invoked when an executor has exited/terminated. Note that any
   * tasks running will have TASK_LOST status updates automagically
   * generated.
   */
  def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int) {
    println("WARNING: executor terminated with status " + status)
  }

  /**
   * Invoked when there is an unrecoverable error in the scheduler or
   * scheduler driver. The driver will be aborted BEFORE invoking this
   * callback.
   */
  def error(driver: SchedulerDriver, message: String) {
    println("ERROR: " + message)
  }

}

object DeliteMesosScheduler {

  private var appArgs: Array[String] = _
  private var driver: MesosSchedulerDriver = _
  private var slaves: List[SlaveID] = Nil
  var activeSlaves = 0
  private val executorId = ExecutorID.newBuilder.setValue("DeliteExecutor").build

  private val remoteLock = new ReentrantLock
  private val remoteCompleted = remoteLock.newCondition
  private var notCompleted = true
  private var remoteResult: Array[ReturnResult] = _
  private lazy val network: ConnectionManager = new ConnectionManager(Config.messagePort)
  private val networkMap = new HashMap[Int, ConnectionManagerId]

  def log(s: String) = if (Config.verbose) println("[master]: " + s)
  def logFromSlave(slaveId: Int, s: String) = if (Config.verbose) println("[slave " + slaveId + "]: " + s)
  def warn(s: String) = println("[master WARNING]: " + s)
  def warnFromSlave(slaveId: Int, s: String) = println("[slave " + slaveId + " WARNING]: " + s)

  def main(args: Array[String]) {
    appArgs = args
    val master = Config.masterAddress
    if (master == "")
      sys.error("No master node specified")

    val sep = java.io.File.separator
    appArgs(0) = Config.deliteHome + sep + appArgs(0) //should be part of the 'delite' script?
    println(appArgs.mkString(", "))
    val noregen = if (Config.noRegenerate) "--noregen" else ""
    val verbose = if (Config.verbose) "-v" else ""
    val executorCmd = Config.deliteHome + sep + "bin" + sep + "delite " + verbose + " " + noregen + " --isSlave -d " + System.getProperty("user.dir") + " -t " + Config.numThreads + " --cuda " + Config.numCuda + " --codecache " + Config.deliteHome+"/generatedCacheSlave " + appArgs.mkString(" ")
    println(executorCmd)

    val executor = ExecutorInfo.newBuilder
      .setExecutorId(executorId)
      .setCommand(CommandInfo.newBuilder.setValue(executorCmd).build)
      .build

    val framework = FrameworkInfo.newBuilder
      .setUser("")
      .setName("Delite Runtime")
      .build

    network.onReceiveMessage((msg: Message, id: ConnectionManagerId) => {
      log("Received [" + msg + "] from [" + id + "]")
      None
    })

    driver = new MesosSchedulerDriver(new DeliteMesosScheduler(executor), framework, master)
    driver.start() //TODO: sanity check successful connection
    Delite.embeddedMain(args, Map())

    network.stop()
    driver.stop()
  }

  def addSlaveToNetwork(info: CommInfo) {
    networkMap.put(info.getSlaveIdx, ConnectionManagerId(info.getSlaveAddress(0), info.getSlavePort(0)))
    //network.sendMessageAsync(networkMap.get(info.getSlaveIdx), Message.createBufferMessage(java.nio.ByteBuffer.allocate(10)))

    if (networkMap.size == slaves.length) { //broadcast network map to slaves
      for (i <- 0 until slaves.length) {
        val slaveInfo = CommInfo.newBuilder.setSlaveIdx(i)
        for (j <- 0 until slaves.length) {
          val networkId = networkMap.get(j)
          slaveInfo.addSlaveAddress(networkId.host)
          slaveInfo.addSlavePort(networkId.port)
        }
        val mssg = DeliteMasterMessage.newBuilder
          .setType(DeliteMasterMessage.Type.INFO)
          .setInfo(slaveInfo)
          .build

        driver.sendFrameworkMessage(executorId, slaves(i), mssg.toByteArray)
      }
    }
  }

  // TODO: move me to scheduler and improve algorithm
  private def schedule(id: String, tpe: RemoteOp.Type, size: Long, stencils: Seq[Stencil], args: Seq[Any]): Array[Int] = tpe match {
    case RemoteOp.Type.INPUT => //don't know the file partitioning, so just distribute to everyone //TODO: fix this?
      Array.fill(slaves.length)(0)

    case RemoteOp.Type.MULTILOOP =>
      def combineLoopBounds(lhs: Option[Array[Int]], rhs: Option[Array[Int]]) = (lhs,rhs) match {
        case (None, r) => r
        case (l, None) => l
        case (l@Some(a), Some(b)) if (java.util.Arrays.equals(a,b)) => l
        case (l@Some(_), Some(_)) => warn("loop inputs are not aligned: will be satisfied with remote reads"); l
      }

      def badStencil(stencil: String, arrayId: String) = {
        warn("op " + id + ": " + stencil + " stencil on RemoteArray " + arrayId + ": will be satisfied with remote reads")
      }

      val stencilsWithArrays = (stencils zip args).filter(_._2.isInstanceOf[RemoteDeliteArray[_]]).map(sa => (sa._1,sa._2.asInstanceOf[RemoteDeliteArray[_]]))
      stencilsWithArrays foreach { sa =>
        sa match {
          case (Empty, a) => badStencil("Unknown", a.id)
          case (All, a) => badStencil("All", a.id)
          case (Const(x), a) => badStencil("Const", a.id)
          case _ => // ok
        }
      }

      val bounds = stencilsWithArrays.map(sa => sa._1.withArray(sa._2))
      bounds.foldLeft(None:Option[Array[Int]])(combineLoopBounds) match {
        case Some(loopBounds) => loopBounds
        case None =>
          // If we don't know the bounds, distribute to everyone with uniform chunks, and let the dust settle where it may (wild west remote reads)
          // This should typically only occur for ops that construct an array out of the ether (like I/O)
          if (size <= 0) Array.fill(0)(1) // run on 1 slave (could run on master)
          else Array.tabulate(slaves.length)(i => (i*size/slaves.length).toInt)
      }
  }

  // We require the serializedArgs to be passed in, because we don't have any type info (manifest) here, which is required to call serialize.
  def launchAllSlaves(id: String, tpe: RemoteOp.Type, size: Long, stencils: Seq[Stencil], args: Seq[Any], serializedArgs: Seq[ByteString]): Array[ReturnResult] = {
    val loopBounds = schedule(id, tpe, size, stencils, args)
    this.remoteResult = new Array(loopBounds.length)
    activeSlaves = loopBounds.length
    //log("sending message to slaves")
    for (slaveIdx <- 0 until loopBounds.length) { //TODO: non-contiguous slaves
      val remoteOp = RemoteOp.newBuilder.setId(Id.newBuilder.setId(id)).setType(tpe)
      for (start <- loopBounds) remoteOp.addStartIdx(start)
      for (arg <- serializedArgs) remoteOp.addInput(arg)

      val mssg = DeliteMasterMessage.newBuilder
        .setType(DeliteMasterMessage.Type.OP)
        .setOp(remoteOp)
        .build

      driver.sendFrameworkMessage(executorId, slaves(slaveIdx), mssg.toByteArray)
    }

    // await results
    var remoteResult: Array[ReturnResult] = null
    remoteLock.lock()
    try {
      while (notCompleted) {
        remoteCompleted.await()
      }
      remoteResult = this.remoteResult
      notCompleted = true
    }
    finally {
      remoteLock.unlock()
    }

    remoteResult
  }

  def requestBulkData(array: RemoteDeliteArray[_]): Array[ReturnResult] = {
    val chunks = array.chunkLengths.length
    this.remoteResult = new Array(chunks)
    activeSlaves = chunks
    for (slaveIdx <- 0 until chunks) {
      val mssg = DeliteMasterMessage.newBuilder
        .setType(DeliteMasterMessage.Type.DATA)
        .setData(RequestData.newBuilder.setId(Id.newBuilder.setId(array.id)))
        .build

      driver.sendFrameworkMessage(executorId, slaves(slaveIdx), mssg.toByteArray)
    }

    // await results
    var remoteResult: Array[ReturnResult] = null
    remoteLock.lock()
    try {
      while (notCompleted) {
        remoteCompleted.await()
      }
      remoteResult = this.remoteResult
      notCompleted = true
    }
    finally {
      remoteLock.unlock()
    }

    remoteResult
  }


  def requestData(id: String, location: Int, idx: Int): ReturnResult = {
    warn("requesting remote read of " + id + " at index " + idx)
    val mssg = DeliteMasterMessage.newBuilder
      .setType(DeliteMasterMessage.Type.DATA)
      .setData(RequestData.newBuilder.setId(Id.newBuilder.setId(id)).setIdx(idx))
      .build.toByteString.asReadOnlyByteBuffer

    val resBytes = network.sendMessageSync(networkMap.get(location), Message.createBufferMessage(mssg)).get.asInstanceOf[BufferMessage].buffers(0).array
    val result = DeliteSlaveMessage.parseFrom(resBytes)
    result.getType match {
      case DeliteSlaveMessage.Type.RESULT => log("remote received"); result.getResult
    }
  }

}
