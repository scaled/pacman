//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SystemResolver {

  public Path resolve (SystemId id) {
    if (!id.platform.equals("jdk")) {
      throw new IllegalArgumentException("Unknown platform " + id);
    }
    if (!id.artifact.equals("tools")) {
      throw new IllegalArgumentException("Unknown JDK artifact " + id);
    }
    for (JDK jdk : JDK.jdks()) {
      if (jdk.version().startsWith(id.version)) {
        return jdk.home.resolve("lib").resolve("tools.jar");
      }
    }
    // fall back to using the running JDK (if it is a JDK) and hope for the best
    return JDK.thisJDK.home.resolve("lib").resolve("tools.jar");
  }
}
