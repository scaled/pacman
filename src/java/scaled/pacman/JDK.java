//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE


package scaled.pacman;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Contains info on a JDK installed on the local machine. */
public class JDK {

  /** The currently running JDK. */
  public static JDK thisJDK = new JDK(thisHome());

  /** All JDKs that could be found on the local machine. */
  public static List<JDK> jdks () {
    if (_jdks == null) {
      _jdks = new ArrayList<>();
      if (isMac) {
        findJDKs(_jdks, "/Library/Java/JavaVirtualMachines");
        findJDKs(_jdks, "/System/Library/Java/JavaVirtualMachines");
      }
      else if (isWin) {} // TODO!
      else if (isLin) {} // TODO!
      // else halp!

      // if our running JDK is just a JRE, move it  to the end of the list,
      // otherwise move it to the start of the list
      _jdks.remove(thisJDK);
      int pos = Files.exists(thisJDK.root()) ? 0 : _jdks.size();
      _jdks.add(pos, thisJDK);
    }
    return _jdks;
  }

  public static void main (String[] args) {
    for (JDK jdk : jdks()) {
      System.out.println(jdk.version() + " -> " + jdk.home);
    }
  }

  /** The home of this JDK installation. */
  public final Path home;

  public JDK (Path home) {
    this.home = home;
    try {
      for (String line : Files.readAllLines(home.resolve("release"))) {
        String[] parts = line.split("=", 2);
        if (parts.length == 2) {
          String key = parts[0], value = parts[1];
          _releaseData.put(key.trim(), value.trim().replaceAll("^\"", "").replaceAll("\"$", ""));
        } else {
          System.err.println("Invalid 'release' line '" + line + "'");
        }
      }
    } catch (Exception e) {
      e.printStackTrace(System.err);
    }
  }

  /** Returns the version of this JDK. Of the form `1.x.x`. */
  public String version () {
    return _releaseData.getOrDefault("JAVA_VERSION", "1.?.?");
  }

  /** Returns the major version of this JDK: 8, 7, 6, etc.
    * Returns `?` if it cannot be determined. */
  public String majorVersion () {
    String[] parts = version().split("\\.", 3);
    return (parts.length == 3 && parts[0].equals("1")) ? parts[1] : "?";
  }

  /** Returns the path to the `java` command for this JDK. */
  public Path binJava () {
    return home.resolve("bin").resolve("java");
  }

  /** The root of the project for this JDK. */
  public Path root () {
    return home.resolve("src.zip");
  }

  @Override public boolean equals (Object other) {
    return (other instanceof JDK) && home.equals(((JDK)other).home);
  }

  @Override public int hashCode () {
    return home.hashCode();
  }

  // parse the 'release' file to get metadata for this JDK install
  private Map<String, String> _releaseData = new HashMap<>();

  private static List<JDK> _jdks;

  private static void findJDKs (List<JDK> jdks, String dir) {
    try {
      Path path = Paths.get(dir);
      if (Files.exists(path)) {
        for (Path subdir : Files.list(path).collect(Collectors.toList())) {
          Path home = subdir.resolve("Contents").resolve("Home");
          if (isHome(home)) jdks.add(new JDK(home));
        }
      }
    } catch (Exception e) {
      e.printStackTrace(System.err);
    }
  }

  private static boolean isHome (Path home) {
    return (Files.exists(home.resolve("bin").resolve("javac")) &&
            Files.exists(home.resolve("release")));
  }

  private static String os = System.getProperty("os.name");
  private static boolean isMac = os.startsWith("Mac OS");
  private static boolean isWin = os.startsWith("Windows");
  private static boolean isLin = os.startsWith("Linux");

  private static Path thisHome () {
    Path home = Paths.get(System.getProperty("java.home"));
    return home.endsWith(Paths.get("jre")) ? home.getParent() : home;
  }
}
