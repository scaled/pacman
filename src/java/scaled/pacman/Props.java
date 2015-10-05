//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

/**
 * A clearinghouse for all values that come from system properties, or the environment.
 */
public class Props {

  public static final String userHome = System.getProperty("user.home");

  public static final String javaHome = System.getProperty("java.home");

  public static final String pathSep = System.getProperty("path.separator");

  public static final String scaledHome = System.getenv("SCALED_HOME");

  public static final boolean ignoreModuleJar = Boolean.getBoolean("pacman.ignore_module_jar");

  public static final boolean debug = Boolean.getBoolean("debug");

  public static String cwd () {
    return System.getProperty("user.dir");
  }
}
