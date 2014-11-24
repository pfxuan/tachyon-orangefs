package tachyon.hadoop.v2;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DelegateToFileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.local.LocalConfigKeys;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Shell;
import org.slf4j.LoggerFactory;

import tachyon.Constants;
import tachyon.hadoop.TFS;

public class TachyonFs extends DelegateToFileSystem {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);
  public static final URI TACHYON_URI = URI.create("tachyon:///");

  /**
   * This constructor has the signature needed by
   * {@link AbstractFileSystem#createFileSystem(java.net.URI, org.apache.hadoop.conf.Configuration)}
   * .
   *
   * @param uri which must be that of localFs
   * @param conf
   * @throws java.io.IOException
   * @throws java.net.URISyntaxException
   */

  TachyonFs(final URI uri, final Configuration conf) throws IOException, URISyntaxException {
    super(uri, new TFS(uri, conf), conf, uri.getScheme(), false);
  }

  TachyonFs(Configuration conf) throws IOException, URISyntaxException {
    this(TACHYON_URI, conf);
  }


  @Override
  public int getUriDefaultPort() {
    return -1; // No default port for file:///
  }

  @Override
  public FsServerDefaults getServerDefaults() throws IOException {
    return LocalConfigKeys.getServerDefaults();
  }

  @Override
  public boolean supportsSymlinks() {
    return true;
  }

  @Override
  public void createSymlink(Path target, Path link, boolean createParent) throws IOException {
    final String targetScheme = target.toUri().getScheme();
    if (targetScheme != null && !"file".equals(targetScheme)) {
      throw new IOException("Unable to create symlink to non-local file " + "system: "
          + target.toString());
    }
    if (createParent) {
      mkdir(link.getParent(), FsPermission.getDirDefault(), true);
    }
    // NB: Use createSymbolicLink in java.nio.file.Path once available
    try {
      Shell.execCommand(Shell.LINK_COMMAND, "-s", new URI(target.toString()).getPath(), new URI(
          link.toString()).getPath());
    } catch (URISyntaxException x) {
      throw new IOException("Invalid symlink path: " + x.getMessage());
    } catch (IOException x) {
      throw new IOException("Unable to create symlink: " + x.getMessage());
    }
  }

  /**
   * Returns the target of the given symlink. Returns the empty string if the given path does not
   * refer to a symlink or there is an error acessing the symlink.
   */
  private String readLink(Path p) {
    /*
     * NB: Use readSymbolicLink in java.nio.file.Path once available. Could use getCanonicalPath in
     * File to get the target of the symlink but that does not indicate if the given path refers to
     * a symlink.
     */
    try {
      final String path = p.toUri().getPath();
      return Shell.execCommand(Shell.READ_LINK_COMMAND, path).trim();
    } catch (IOException x) {
      return "";
    }
  }

  /**
   * Return a FileStatus representing the given path. If the path refers to a symlink return a
   * FileStatus representing the link rather than the object the link refers to.
   */
  @Override
  public FileStatus getFileLinkStatus(final Path f) throws IOException {
    String target = readLink(f);
    try {
      FileStatus fs = getFileStatus(f);
      // If f refers to a regular file or directory
      if ("".equals(target)) {
        return fs;
      }
      // Otherwise f refers to a symlink
      return new FileStatus(fs.getLen(), false, fs.getReplication(), fs.getBlockSize(),
          fs.getModificationTime(), fs.getAccessTime(), fs.getPermission(), fs.getOwner(),
          fs.getGroup(), new Path(target), f);
    } catch (FileNotFoundException e) {
      /*
       * The exists method in the File class returns false for dangling links so we can get a
       * FileNotFoundException for links that exist. It's also possible that we raced with a delete
       * of the link. Use the readBasicFileAttributes method in java.nio.file.attributes when
       * available.
       */
      if (!"".equals(target)) {
        return new FileStatus(0, false, 0, 0, 0, 0, FsPermission.getDefault(), "", "", new Path(
            target), f);
      }
      // f refers to a file or directory that does not exist
      throw e;
    }
  }

  @Override
  public Path getLinkTarget(Path f) throws IOException {
    /*
     * We should never get here. Valid local links are resolved transparently by the underlying
     * local file system and accessing a dangling link will result in an IOException, not an
     * UnresolvedLinkException, so FileContext should never call this function.
     */
    throw new AssertionError();
  }

}
