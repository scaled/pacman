//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SystemResolver {

  public final Path jreHome = Paths.get(Props.javaHome);
  public final Path javaHome = jreHome.getParent();

  public Path resolve (SystemId id) {
    if (id.platform.equals("jdk")) {
      if (id.artifact.equals("tools")) {
        return javaHome.resolve("lib").resolve("tools.jar");
      }
      throw new IllegalArgumentException("Unknown JDK artifact " + id);
    }
    throw new IllegalArgumentException("Unknown platform " + id);
  }
}
