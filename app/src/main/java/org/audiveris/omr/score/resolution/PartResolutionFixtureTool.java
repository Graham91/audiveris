//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                       P a r t R e s o l u t i o n F i x t u r e T o o l                        //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command line entry point to replay part-resolution fixtures without any API call.
 * <p>
 * Commands:
 * <ul>
 * <li><code>list</code>: list saved fixtures
 * <li><code>rebuild [fixture] [outDir]</code>: rebuild corrected MusicXML (newest fixture by
 * default; <code>fixture</code> is a folder path or a name inside the fixtures root)
 * <li><code>import runDir sourceXmlOrMxl</code>: create a fixture from an existing debug run plus
 * the original MusicXML export of the same book
 * </ul>
 */
public abstract class PartResolutionFixtureTool
{
    private PartResolutionFixtureTool ()
    {
    }

    public static void main (String[] args)
        throws Exception
    {
        final String command = (args.length > 0) ? args[0] : "rebuild";

        switch (command) {
            case "list" -> list();
            case "rebuild" -> rebuild(args);
            case "import" -> importRun(args);
            default -> usage();
        }
    }

    private static void list ()
        throws Exception
    {
        final Path root = PartResolutionSettings.getFixtureFolder();
        System.out.println("Fixtures root: " + root);

        for (Path dir : PartResolutionFixture.list(root)) {
            System.out.println("  " + PartResolutionFixture.describe(dir));
        }
    }

    private static void rebuild (String[] args)
        throws Exception
    {
        final Path root = PartResolutionSettings.getFixtureFolder();
        Path fixture;

        if (args.length > 1) {
            fixture = Path.of(args[1]);

            if (!Files.isDirectory(fixture)) {
                fixture = root.resolve(args[1]);
            }
        } else {
            final List<Path> all = PartResolutionFixture.list(root);

            if (all.isEmpty()) {
                System.out.println("No fixture found in " + root);
                return;
            }

            fixture = all.get(0);
        }

        final Path out = (args.length > 2) ? Path.of(args[2]) : null;
        System.out.println(PartResolutionFixture.rebuild(fixture, out));
    }

    private static void importRun (String[] args)
        throws Exception
    {
        if (args.length < 3) {
            usage();
            return;
        }

        final Path fixture = PartResolutionFixture.importRun(
                Path.of(args[1]),
                Path.of(args[2]),
                PartResolutionSettings.getFixtureFolder());
        System.out.println("Fixture created: " + fixture);
        System.out.println(PartResolutionFixture.rebuild(fixture, null));
    }

    private static void usage ()
    {
        System.out.println("Usage: list | rebuild [fixture] [outDir] | import <runDir> <source.xml|mxl>");
    }
}
