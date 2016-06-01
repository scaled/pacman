//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** File utilities. */
public class Filez {

  /** Deletes a file or empty directory, setting its write permissions if necessary. */
  public static void safeDelete (Path path) throws IOException {
    if (!Files.isWritable(path)) path.toFile().setWritable(true);
    Files.delete(path);
  }

  /** Deletes {@code dir} and all of its contents. */
  public static void deleteAll (Path dir) throws IOException {
    if (!Files.exists(dir)) return; // our job is already done
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
      throws IOException {
        if (!attrs.isDirectory()) safeDelete(file);
        return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult postVisitDirectory (Path dir, IOException exn)
      throws IOException {
        if (exn != null) throw exn;
        safeDelete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Copies the contents of {@code fromDir} into {@code toDir} recursively.
    * The directory structure under {@code fromDir} is replicated into {@code toDir} as needed. */
  public static void copyAll (Path fromDir, Path toDir) throws IOException {
    Files.createDirectories(toDir);
    Files.walkFileTree(fromDir, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult preVisitDirectory (Path dir, BasicFileAttributes attrs)
      throws IOException {
        Path targetDir = toDir.resolve(fromDir.relativize(dir));
        if (!Files.exists(targetDir)) Files.createDirectory(targetDir);
        return FileVisitResult.CONTINUE;
      }

      @Override public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
      throws IOException {
        Files.copy(file, toDir.resolve(fromDir.relativize(file)),
                   StandardCopyOption.REPLACE_EXISTING);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Scans {@code dir} for any file that is newer than {@code stamp}.
    * @return true if a newer file was found, false if not. */
  public static boolean existsNewer (long stamp, Path dir) throws IOException {
    if (!Files.exists(dir)) return false;
    boolean[] sawNewer = new boolean[1]; // false
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
      throws IOException {
        if (attrs.lastModifiedTime().toMillis() < stamp) return FileVisitResult.CONTINUE;
        sawNewer[0] = true;
        return FileVisitResult.TERMINATE; // we can stop now
      }
    });
    return sawNewer[0];
  }
}
