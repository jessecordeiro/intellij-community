package com.intellij.remoteDev.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileSystemUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.Decompressor
import com.intellij.util.io.DigestUtil
import com.intellij.util.io.exists
import com.intellij.util.io.isDirectory
import org.jetbrains.annotations.ApiStatus
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.function.BiConsumer
import kotlin.io.path.*
import com.intellij.util.io.Decompressor.Entry.Type as EntryType

@ApiStatus.Experimental
object FileManifestUtil {
  private val logger = Logger.getInstance(javaClass)
  const val ManifestFileName = ".manifest.txt"
  const val HashSeed = "e69b0a64-b91a-4da6-bc80-35828c9a97d1"

  class ManifestGenerator(private val targetDir: Path, private val includeInManifest: (Path) -> Boolean) : BiConsumer<Decompressor.Entry, Path> {
    private val list = mutableListOf<String>()

    override fun accept(entry: Decompressor.Entry, path: Path) {
      if (!includeInManifest(path)) return

      require(entry.name != ManifestFileName) { "There already is a manifest file in archive." }
      path.relativeToOrNull(targetDir) ?: "Extraction path ${path.absolutePathString()} is not relative to $targetDir: ${path.absolutePathString()}"

      val name = entry.name.let { if (entry.type == EntryType.DIR) "$it/" else it }

      val mode = if (SystemInfo.isWindows) 0 else {
        // archives may contain some funky permissions, best to normalize that
        val normalizedMode = when(entry.type) {
          EntryType.FILE -> {
            val isOriginallyExecutable = (entry.mode and 0b001001001) > 0 // --x--x--x
            if (isOriginallyExecutable)      33261 // -rwxr-xr-x, octal 0100755
            else                             33188 // -rw-r--r--, octal 0100644
          }
          // symlink permissions don't have meaning anyway, and we can't even set them to be consistent across OS:
          // https://bugs.openjdk.java.net/browse/JDK-8220793 can't set attributes for symlinks pointing to non-existing files
          EntryType.SYMLINK -> -1
          EntryType.DIR ->     16877 // drwxr-xr-x, octal 0040755
          else -> error("Unknown entry type for ${entry.name}")
        }

        if (entry.type != EntryType.SYMLINK) path.setAttribute("unix:mode", normalizedMode, LinkOption.NOFOLLOW_LINKS)
        normalizedMode
      }

      val attributes = readAttributesNoFollowLinks(path)
      if (entry.type != EntryType.SYMLINK) require(attributes.mode == mode) { "$name: expected mode: $mode, on disk: ${attributes.mode}" }

      addManifestEntry(name, entry.type, mode, attributes.size, attributes.lastModifiedTime)
    }

    private data class FileAttributes(val mode: Int, val lastModifiedTime: FileTime, val size: Long)

    private fun addManifestEntry(name: String, type: EntryType, mode: Int, size: Long, lastModifiedTime: FileTime) {
      when(type) {
        EntryType.SYMLINK -> {
          list.add("$name L ${size} ${lastModifiedTime.toMillis() / 1000}")
        }
        EntryType.FILE -> {
          list.add("$name F ${Integer.toOctalString(mode)} ${size} ${lastModifiedTime.toMillis() / 1000}")
        }
        EntryType.DIR -> {
          list.add("$name D ${Integer.toOctalString(mode)}")
        }
      }
    }

    private fun readAttributesNoFollowLinks(path: Path) : FileAttributes {
      val attributeList = mutableListOf("lastModifiedTime", "size")
      if (!SystemInfo.isWindows) attributeList.add(0, "unix:mode")

      val attrs = Files.readAttributes(path, attributeList.joinToString(","), LinkOption.NOFOLLOW_LINKS)
      return FileAttributes(attrs["mode"] as? Int ?: 0, attrs["lastModifiedTime"] as FileTime, attrs["size"] as Long)
    }

    fun calculateForExistingDirectory() {
      require(targetDir.isDirectory()) { "$targetDir is not a directory" }
      require(list.isEmpty())

      Files.walkFileTree(targetDir, ManifestFileVisitor())
    }

    inner class ManifestFileVisitor : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!includeInManifest(file)) return FileVisitResult.CONTINUE
        if (file.name == ManifestFileName) return FileVisitResult.CONTINUE

        val name = file.relativeTo(targetDir).toString().replace("\\", "/")

        val type = when {
          file.isSymbolicLink() -> EntryType.SYMLINK
          file.isRegularFile()  -> EntryType.FILE
          else -> error("Unknown file for ${file.absolutePathString()}")
        }

        val attributes = readAttributesNoFollowLinks(file)

        addManifestEntry(name, type, attributes.mode, attributes.size, attributes.lastModifiedTime)

        return FileVisitResult.CONTINUE
      }

      override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
        if (exc is NoSuchFileException && file != null) {
          try {
            val isSymlink = FileSystemUtil.getAttributes(file.toFile())?.isSymLink == true
            val isBrokenSymlink = isSymlink && !file.exists()
            if (isBrokenSymlink && file.isDirectory(LinkOption.NOFOLLOW_LINKS))
              return FileVisitResult.CONTINUE
          } catch (e: Throwable){
          }
        }

        return super.visitFileFailed(file, exc)
      }

      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
        val isSymlink = FileSystemUtil.getAttributes(dir.toFile())?.isSymLink == true
        if (isSymlink) return FileVisitResult.SKIP_SUBTREE
        if (!includeInManifest(dir)) return FileVisitResult.CONTINUE
        if (dir == targetDir) return FileVisitResult.CONTINUE

        val name = dir.relativeTo(targetDir).toString().replace("\\", "/") + "/"
        val type = EntryType.DIR
        val attributes = readAttributesNoFollowLinks(dir)

        addManifestEntry(name, type, attributes.mode, attributes.size, attributes.lastModifiedTime)

        return FileVisitResult.CONTINUE
      }
    }

    fun generate(): String {
      val contentPart = list.sorted().joinToString("\n")
      // Prevent easy tampering
      return "${DigestUtil.sha256Hex((HashSeed + contentPart).toByteArray(StandardCharsets.UTF_8))}\n$contentPart"
    }

    fun writeToDisk(targetDir: Path) {
      targetDir.resolve(ManifestFileName).writeText(generate(), StandardCharsets.UTF_8)
    }
  }

  fun decompressWithManifest(archiveFile: Path, targetDir: Path, includeInManifest: (Path) -> Boolean) {
    if (targetDir.exists()) error("$targetDir already exists, refusing to extract to it")

    val manifestor = ManifestGenerator(targetDir, includeInManifest)
    when {
      archiveFile.extension.equals("zip", ignoreCase = true) || archiveFile.extension.equals("sit", ignoreCase = true) ->
        Decompressor.Zip(archiveFile)
          .withZipExtensions()
          .allowEscapingSymlinks(false)
          .postProcessor(manifestor)
          .extract(targetDir)
      archiveFile.name.endsWith(".tar.gz", ignoreCase = true) ->
        Decompressor.Tar(archiveFile)
          .allowEscapingSymlinks(false)
          .postProcessor(manifestor)
          .extract(targetDir)
      else -> error("Unknown archive extension in file name: ${archiveFile.name}")
    }

    manifestor.writeToDisk(targetDir)
  }

  fun generateDirectoryManifest(root: Path, includeInManifest: (Path) -> Boolean): String {
    val manifestor = ManifestGenerator(root, includeInManifest)
    manifestor.calculateForExistingDirectory()
    return manifestor.generate()
  }

  fun isUpToDate(root: Path, includeInManifest: (Path) -> Boolean): Boolean {
    val manifestFile = root.resolve(ManifestFileName)
    if (manifestFile.notExists()) {
      logger.info("isUpToDate false for '$root': manifest file $manifestFile does not exist")
      return false
    }

    val manifestFileContent = manifestFile.readText(StandardCharsets.UTF_8)
    val actualOnDiskContent = generateDirectoryManifest(root, includeInManifest)

    if (manifestFileContent == actualOnDiskContent) {
      logger.info("isUpToDate true for '$root': manifest file $manifestFile contains actual information")
      return true
    }

    logger.info("isUpToDate false for '$root': manifest file $manifestFile differs from on disk content\n" +
                "on disk content:\n$actualOnDiskContent\n" +
                "saved manifest in $manifestFile content:\n$manifestFileContent")
    return false
  }

  data class ExtractDirectory(val path: Path, val isUpToDate: Boolean)

  /**
   * Get extract directory state (path to directory and if directory is up-to-date).
   *
   * @param path - base path to extract directory
   * @param filterPaths - filter files to check for up-to-date state
   */
  fun getExtractDirectory(path: Path, filterPaths: (Path) -> Boolean): ExtractDirectory {
    val suffix = ".${ProcessHandle.current().pid()}.${System.currentTimeMillis()}"

    val retriesCount = 100

    (1..retriesCount).forEach moveFolder@{ attempt ->
      val destinationPath = if (attempt <= 1) path else Path.of("$path-$attempt")

      val isUpToDate = isUpToDate(destinationPath, filterPaths)
      if (isUpToDate) {
        logger.info("All files inside extract directory '$destinationPath' are up-to-date")
        return ExtractDirectory(destinationPath, true)
      }

      if (!destinationPath.exists()) {
        logger.info("Destination does not exist, returning $destinationPath")
        return ExtractDirectory(destinationPath, false)
      }

      // Detect any locked files inside. If a directory was safe to rename, it should be safe to write to (or delete).
      val renamedPath = File("${destinationPath}${suffix}").toPath()
      try {
        // Try to move the whole base path into a temp directory.
        Files.move(destinationPath, renamedPath, StandardCopyOption.ATOMIC_MOVE)
      } catch (t: Throwable) {
        logger.debug(
          "Rename '$destinationPath' to '$renamedPath' has failed. Probably file is locked by another process. Trying the next one.")
        return@moveFolder
      } finally {
        try {
          FileUtil.delete(renamedPath)
        }
        catch (t: Throwable) {
          logger.warn("Unable to delete renamed destination (generally should not happen): $renamedPath", t)
        }
      }

      logger.info("Destination was deleted, returning path '$destinationPath'")
      return ExtractDirectory(destinationPath, false)
    }

    throw IllegalStateException("Exceeded $retriesCount retries to get safe destination based on '$path'.")
  }
}
