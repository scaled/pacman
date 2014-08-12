//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

/** Ye olde log facade. */
public class Log {

  /** The target of Pacman logging. This goes to stderr, unless/until Scaled takes over the JVM at
    * which point it reroutes it to the *messages* buffer. */
  public static Target target = new Target() {
    public void log (String msg) { System.err.println(msg); }
    public void log (String msg, Throwable error) { log(msg); error.printStackTrace(System.err); }
  };

  /**
   * Records {@code msg} plus {@code [key=value, ...]} to the log. If the last {@code keyVals}
   * argument is a lone exception, its stack trace will be logged.
   */
  public static void log (String msg, String key, Object value, Object... keyVals) {
    StringBuilder sb = new StringBuilder(msg);
    sb.append(" [").append(key).append("=").append(value);
    int ii = 0; for (int ll = keyVals.length-1; ii < ll; ii += 2) {
      sb.append(", ").append(keyVals[ii]).append("=").append(keyVals[ii+1]);
    }
    sb.append("]");
    if (ii >= keyVals.length || !(keyVals[ii] instanceof Throwable)) log(sb.toString());
    else log(sb.toString(), (Throwable)keyVals[ii]);
  }

  /** Records {@code msg} to the log. */
  public static void log (String msg) {
    target.log(msg);
  }

  /** Records {@code msg} and {@code error} to the log. */
  public static void log (String msg, Throwable error) {
    target.log(msg, error);
  }

  public static interface Target {
    /** Records {@code msg} to the log. */
    void log (String msg);

    /** Records {@code msg} and {@code error} to the log. */
    void log (String msg, Throwable error);
  }
}
