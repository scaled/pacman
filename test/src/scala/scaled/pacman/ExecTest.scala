//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.pacman

import java.nio.file.Paths
import org.junit.Assert._
import org.junit._

class ExecTest {

  val cwd = Paths.get(Props.cwd)

  @Test def testOutput () :Unit = {
    val out = Exec.exec(cwd, "echo", "peanut").output()
    assertEquals(1, out.size)
    assertEquals("peanut", out.get(0))
  }
}
