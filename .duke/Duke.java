import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

/** Duke's command-line program and interactive shell interface. */
interface Duke {
  String VERSION = "2023.02.28+10.23";

  /** Duke's command-line program. */
  static void main(String... args) {
    var context = Context.getContext();
    var verbose = context.flags().verbose();
    if (verbose) context.println(context);

    var arguments = new ArrayDeque<>(List.of(args));
    if (arguments.isEmpty()) {
      context.print(
          """
          Usage: Duke.java <operation> [<arguments>]

          Supported operations include:
            help      Show more helpful information
            run       Run a sequence of tools with their arguments
            status    Show the working directory status
            version   Show version information
          """);
      if (verbose) {
        context.print("\n");
        Duke.status();
      }
      return;
    }

    var operation = arguments.removeFirst();
    switch (operation) {
      case "find" -> Duke.find(arguments.toArray(String[]::new));
      case "help", "?", "-h", "--help" -> Duke.help();
      case "run", "+" -> Duke.run(arguments.toArray(String[]::new));
      case "status", "~" -> Duke.status();
      case "version", "-v", "--version" -> Duke.version();
      default -> {
        var message = "Operation `" + operation + "` is not supported.";
        System.err.println(message);
      }
    }
  }

  /** Find files applying an optional glob or regex path matcher. */
  static void find(String... args) {
    var start = Path.of("");
    var syntaxAndPattern = args.length == 0 ? "glob:**/*" : args[0];
    var include = start.getFileSystem().getPathMatcher(syntaxAndPattern);
    var exclude = start.getFileSystem().getPathMatcher("glob:.git/**");
    PathMatcher matcher = path -> include.matches(path) && !exclude.matches(path);
    Context.find(start, 99, matcher).forEach(Context.getContext()::println);
  }

  /** Print detailed help information. */
  static void help() {
    var context = Context.getContext();
    context.print(
        """
        Usage: Duke.java <operation> [<arguments>]

        <here be more help>
        """);
  }

  /** Run a sequence of tool calls. */
  static void run(String... args) {
    Context.getContext().run(args);
  }

  /** Print status information. */
  static void status() {
    Context.getContext().printStatus();
  }

  /** Print version information. */
  static void version() {
    Context.getContext().printVersion();
  }

  /** Runtime components and */
  record Context(Flags flags, Paths paths, PrintWriter printer) {
    List<Path> getModuleCompilationUnits() {
      var directory = paths.duke();
      if (Files.notExists(directory)) return List.of();
      var fs = directory.getFileSystem();
      var top = fs.getPathMatcher("glob:*/module-info.java");
      var sub = fs.getPathMatcher("glob:sub/*/module-info.java");
      return find(directory, 3, path -> top.matches(path) || sub.matches(path));
    }

    String getVersion() {
      var found = ModuleFinder.of(paths.bin()).find("run.duke");
      if (found.isEmpty()) return VERSION;
      return "%s (%s)".formatted(VERSION, found.get().descriptor().toNameAndVersion());
    }

    void debug(Object message) {
      if (flags.verbose) println(message);
    }

    void print(Object message) {
      printer.print(message);
      printer.flush();
    }

    void println(Object message) {
      printer.println(message);
      printer.flush();
    }

    void printStatus() {
      var status = new StatusPrinter();
      println("Duke.java " + getVersion());
      if (flags.verbose) status.printCodeSourceLocation();
      status.printJavaRuntimeInformation();
      status.printOperatingSystemInformation();
      status.printModuleCompilationUnits();
      status.printModulesInBinFolder();
    }

    void printVersion() {
      println(getVersion());
    }

    void run(String... args) {
      var module = "run.duke";
      var tools = new ToolsHelper();
      var bin = paths.bin();
      var src = Path.of("src", module, "main", "java");
      // 1. "run.duke"
      if (ModuleFinder.of(bin).find(module).isEmpty() || Files.isDirectory(src)) {
        var files = new FilesHelper();
        var version = environment("VERSION", "early-access");
        if (Files.isDirectory(src)) { // re-build "run.duke" module
          var moduleVersionBuilder = new StringBuilder();
          {
            var number = property("build.version.number", "0");
            var early = property("build.version.pre-release", "ea");
            var build = new GitHubHelper().getShaOrIsoInstant();
            moduleVersionBuilder.append(number);
            if (!early.isBlank()) moduleVersionBuilder.append('-').append(early);
            moduleVersionBuilder.append('+').append(build);
          }
          var moduleVersion = ModuleDescriptor.Version.parse(moduleVersionBuilder.toString());
          files.deleteDirectories(bin);
          files.deleteDirectories(paths.out());
          tools.run(
              "javac",
              "--release=17",
              "--module=" + module,
              "--module-source-path=src/*/main/java",
              "--module-version=" + moduleVersion,
              "-d",
              ".duke/out/run/classes");
          var jarFile = module + "@" + version + ".jar";
          var jar = files.createDirectories(bin).resolve(jarFile);
          var jarMainClass = module + ".Main";
          tools.run(
              "jar",
              "--create",
              "--file=" + jar,
              "--main-class=" + jarMainClass,
              "-C",
              ".duke/out/run/classes/" + module,
              ".");
        } else {
          var download = "https://github.com/sormuras/duke/releases/download";
          var filename = module + "@" + version + ".jar";
          var source = URI.create(download + "/" + version + "/" + filename);
          var target = paths.bin().resolve(filename);
          files.download(source, target);
        }
      }
      // 2. "project" and all submodules
      var units = getModuleCompilationUnits();
      if (!units.isEmpty()) {
        var names = units.stream().map(Path::getParent).map(Path::getFileName);
        var modules = names.map(Path::toString).toList();
        var moduleSourcePaths = List.of(paths.duke().toString(), paths.duke("sub").toString());
        tools.run(
            "javac",
            "--module=" + String.join(",", modules),
            "--module-source-path=" + String.join(File.pathSeparator, moduleSourcePaths),
            "--module-path=" + bin,
            "-d",
            bin);
      }
      // 3. run duke ...
      if (flags().verbose()) new StatusPrinter().printModulesInBinFolder();
      var arguments =
          Stream.of("--module-path", bin, "--add-modules", "ALL-DEFAULT", "--module", "run.duke");
      tools.java(Stream.concat(arguments, Stream.of(args)));
    }

    record Flags(boolean verbose, boolean dryRun) {}

    record Paths(Path duke) {
      Path duke(String first, String... more) {
        return duke.resolve(Path.of(first, more));
      }

      Path bin() {
        return duke("bin");
      }

      Path out() {
        return duke("out");
      }
    }

    private final class StatusPrinter {
      void printCodeSourceLocation() {
        var domain = Duke.class.getProtectionDomain();
        var source = domain.getCodeSource();
        if (source != null) {
          var location = source.getLocation();
          if (location != null) {
            println("Code source location: " + location);
          } else println("No code location available for " + source);
        } else println("No code source available for " + domain);
      }

      void printJavaRuntimeInformation() {
        var version = Runtime.version();
        var feature = version.feature();
        var home = Path.of(System.getProperty("java.home")).toUri();
        println("Java %s (build: %s, home: %s)".formatted(feature, version, home));
      }

      void printOperatingSystemInformation() {
        var name = System.getProperty("os.name");
        var version = System.getProperty("os.version");
        var architecture = System.getProperty("os.arch");
        println("%s (version: %s, architecture: %s)".formatted(name, version, architecture));
      }

      void printModuleCompilationUnits() {
        var units = getModuleCompilationUnits();
        println("Module Compilation Units: " + units.size());
        var stream = units.stream().map(Path::toUri).sorted();
        stream.map(uri -> "  " + uri).forEach(Context.this::println);
      }

      void printModulesInBinFolder() {
        var modules = ModuleFinder.of(paths.bin()).findAll();
        println("Modules in " + paths.bin().toUri() + ": " + modules.size());
        modules.stream()
            .map(ModuleReference::descriptor)
            .sorted()
            .map(module -> "  " + module.toNameAndVersion())
            .forEach(Context.this::println);
      }
    }

    private final class FilesHelper {
      Path createDirectories(Path path) {
        try {
          return Files.createDirectories(path);
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      }

      void deleteDirectories(Path path) {
        var start = path.normalize().toAbsolutePath();
        if (Files.notExists(start)) return;
        for (var root : start.getFileSystem().getRootDirectories()) {
          if (start.equals(root)) {
            debug("deletion of root directory?! " + path);
            return;
          }
        }
        debug("delete directory tree " + start);
        try (var stream = Files.walk(start)) {
          var files = stream.sorted((p, q) -> -p.compareTo(q));
          for (var file : files.toArray(Path[]::new)) Files.deleteIfExists(file);
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      }

      void download(URI source, Path target) {
        try (var stream = source.toURL().openStream()) {
          createDirectories(target.getParent());
          Files.copy(stream, target);
        } catch (Exception exception) {
          throw new Error(exception);
        }
      }
    }

    private static final class GitHubHelper {
      String getShaOrIsoInstant() {
        var sha = System.getenv("GITHUB_SHA");
        if (sha != null) return sha.substring(0, Math.min(7, sha.length()));
        var now = ZonedDateTime.now();
        return now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_INSTANT);
      }
    }

    private final class ToolsHelper {
      void run(String name, Object... arguments) {
        var provider = ToolProvider.findFirst(name).orElseThrow(() -> new Error("Tool? " + name));
        // var out = new PrintWriter(System.out, true);
        var err = new PrintWriter(System.err, true);
        var args = Stream.of(arguments).map(Object::toString).toList();
        println("* " + name + " " + String.join(" ", args));
        var code = provider.run(printer, err, args.toArray(String[]::new));
        if (code == 0) return;
        throw new Error(name + " finished with error code: " + code);
      }

      void java(Stream<Object> arguments) {
        var name = "java";
        var launcher = Path.of(System.getProperty("java.home"), "bin", name);
        var builder = new ProcessBuilder(launcher.toString());
        builder.command().addAll(arguments.map(Object::toString).toList());
        try {
          println("* " + String.join(" ", builder.command()));
          var process = builder.start();
          // var out = new PrintWriter(System.out, true);
          var err = new PrintWriter(System.err, true);
          new Thread(new LinePrinter(process.getInputStream(), printer), name + "-out").start();
          new Thread(new LinePrinter(process.getErrorStream(), err), name + "-err").start();
          var code = process.waitFor();
          if (code == 0) return;
          throw new Error(name + " finished with error code: " + code);
        } catch (InterruptedException ignore) {
        } catch (Exception exception) {
          exception.printStackTrace(System.err);
        }
      }

      private record LinePrinter(InputStream stream, PrintWriter writer) implements Runnable {
        @Override
        public void run() {
          new BufferedReader(new InputStreamReader(stream)).lines().forEach(writer::println);
        }
      }
    }

    private static Context ofSystem() {
      var flags =
          new Flags(
              Boolean.parseBoolean(property("verbose", environment("VERBOSE", "false"))),
              Boolean.parseBoolean(property("dry-run", environment("DRY-RUN", "false"))));
      var paths = new Paths(Path.of(property("directory", ".duke")));
      var out = new PrintWriter(System.out, true);
      return new Context(flags, paths, out);
    }

    private static String environment(String name, String defaultValue) {
      var key = "DUKE_" + name;
      return System.getenv().getOrDefault(key, defaultValue);
    }

    private static String property(String name, String defaultValue) {
      var key = "-Duke-" + name;
      return System.getProperty(key.substring(2), defaultValue);
    }

    private static List<Path> find(Path start, int maxDepth, PathMatcher matcher) {
      try (var stream =
          Files.find(start, maxDepth, (p, a) -> matcher.matches(start.relativize(p)))) {
        return stream.sorted().toList();
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    private static Context getContext() {
      return CONTEXT;
    }

    private static final Context CONTEXT = ofSystem();
  }
}
