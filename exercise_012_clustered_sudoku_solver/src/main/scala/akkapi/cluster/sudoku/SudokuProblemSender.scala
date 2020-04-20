package akkapi.cluster.sudoku

import java.io.File

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}

object SudokuProblemSender {

  // Replaced the traditional ADT implemented with case objects/classes
  // extending a sealed trait with a Dotty enum
  enum Command {
    case SendNewSudoku
  }

  // Export the generated enum members that are going to be this actor's
  // protocol
  export Command._

  // Use a Dotty Union type to define the actor's internal protocol
  type CommandAndResponses = Command | SudokuSolver.Response

  private val rowUpdates: Vector[SudokuDetailProcessor.RowUpdate] =
    SudokuIO.readSudokuFromFile(new File("sudokus/001.sudoku"))
      .map { case (rowIndex, update) => SudokuDetailProcessor.RowUpdate(rowIndex, update) }

  // A factory method for this actor's behavior: from the outside, it exposes
  // it's protocol, whereas on the inside, it can also process responses
  def apply(sudokuSolver: ActorRef[SudokuSolver.Command],
            sudokuSolverSettings: SudokuSolverSettings): Behavior[Command] =
    Behaviors.setup[CommandAndResponses] { context =>
      Behaviors.withTimers { timers =>
        new SudokuProblemSender(sudokuSolver, context, timers, sudokuSolverSettings).sending()
      }
    }.narrow // Restrict the protocol to the strict minimum
}

class SudokuProblemSender private (sudokuSolver: ActorRef[SudokuSolver.Command],
                                   context: ActorContext[SudokuProblemSender.CommandAndResponses],
                                   timers: TimerScheduler[SudokuProblemSender.CommandAndResponses],
                                   sudokuSolverSettings: SudokuSolverSettings) {
  import SudokuProblemSender._

  private val initialSudokuField = rowUpdates.toSudokuField

  private val rowUpdatesSeq = LazyList.continually(
    Vector(
      initialSudokuField,
      initialSudokuField.flipVertically,
      initialSudokuField.flipHorizontally,
      initialSudokuField.flipHorizontally.flipVertically,
      initialSudokuField.flipVertically.flipHorizontally,
      initialSudokuField.columnSwap(0,1),
      initialSudokuField.rowSwap(4,5).rowSwap(0, 2),
      initialSudokuField.randomSwapAround,
      initialSudokuField.randomSwapAround,
      initialSudokuField.rotateCW,
      initialSudokuField.rotateCCW,
      initialSudokuField.rotateCW.rotateCW,
      initialSudokuField.transpose,
      initialSudokuField.randomSwapAround,
      initialSudokuField.rotateCW.transpose,
      initialSudokuField.randomSwapAround,
      initialSudokuField.rotateCCW.transpose,
      initialSudokuField.randomSwapAround,
      initialSudokuField.randomSwapAround,
      initialSudokuField.flipVertically.transpose,
      initialSudokuField.flipVertically.rotateCW,
      initialSudokuField.columnSwap(4,5).columnSwap(0, 2).rowSwap(3,4),
      initialSudokuField.rotateCW.rotateCW.transpose
    ).map(_.toRowUpdates)).flatten.iterator

  private val problemSendInterval = sudokuSolverSettings.ProblemSender.SendInterval
  timers.startTimerAtFixedRate(SendNewSudoku, problemSendInterval) // on a 5 node RPi 4 based cluster in steady state, this can be lowered to about 6ms

  def sending(): Behavior[CommandAndResponses] = Behaviors.receiveMessagePartial {
    case SendNewSudoku =>
      context.log.debug("sending new sudoku problem")
      val nextRowUpdates = rowUpdatesSeq.next
      context.log.info(s"==> ProblemSender sending $nextRowUpdates")
      sudokuSolver ! SudokuSolver.InitialRowUpdates(nextRowUpdates, context.self)
      Behaviors.same
    case solution: SudokuSolver.SudokuSolution =>
      context.log.info(s"${SudokuIO.sudokuPrinter(solution)}")
      Behaviors.same
  }
}

