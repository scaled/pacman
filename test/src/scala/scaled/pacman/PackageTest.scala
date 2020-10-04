//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.pacman

import java.net.URI
import java.nio.file.Paths
import java.util.Collections
import org.junit.Assert._
import org.junit._

class PackageTest {
  import scala.collection.JavaConverters._

  val cwd = Paths.get("")

  val scaledApi = Seq(
    "   name: scaled-api",
    " source: git:https://github.com/scaled/scaled-api.git",
    "version: 1.0",
    "descrip: The API against which Scaled extensions are written.",
    " weburl: https://github.com/scaled/scaled-api/",
    "license: New BSD"
  )
  val scaledSource = new Source(Source.VCS.GIT, new URI("https://github.com/scaled/scaled-api.git"))

  @Test def testValid () :Unit = {
    val info = new Package(cwd, scaledApi.asJava)
    assertEquals(scaledSource, info.source)
    assertEquals(Collections.emptyList(), info.module(Module.DEFAULT).depends)
    assertTrue(info.errors.isEmpty)
  }

  @Test def testExtraCruft () :Unit = {
    val extra = Seq(
      "bezelnut: ruh ruh",
      " peanuts: and popcorn"
    )
    val info = new Package(cwd, (scaledApi ++ extra).asJava)
    assertEquals(scaledSource, info.source)
    assertEquals(2, info.errors.size)
  }

  @Test def testDoubleSource () :Unit = {
    val extra = Seq(
      " source: git:https://github.com/scaled/scaled-peanut.git"
    )
    val info = new Package(cwd, (scaledApi ++ extra).asJava)
    assertEquals(scaledSource, info.source)
    assertEquals(1, info.errors.size)
    assertTrue(info.errors.get(0) startsWith "'source'")
  }

  @Test def testDepends () :Unit = {
    val extra = Seq(
      " depend: git:https://github.com/scaled/java-mode.git",
      " depend: mvn:com.samskivert.scaled:textmate-grammar:1.0-SNAPSHOT:jar"
    )
    val info = new Package(cwd, (scaledApi ++ extra).asJava)
    assertEquals(scaledSource, info.source)
    val javaSource = new Source(Source.VCS.GIT, new URI("https://github.com/scaled/java-mode.git"))
    val tmRepoId = new RepoId("com.samskivert.scaled", "textmate-grammar", "1.0-SNAPSHOT", "jar")
    info.errors.asScala foreach println
    assertEquals(0, info.errors.size)
    assertEquals(List(new Depend(javaSource, Depend.Scope.MAIN),
                      new Depend(tmRepoId, Depend.Scope.MAIN)),
                 info.module(Module.DEFAULT).depends.asScala)
  }
}
