package forge.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.ability.ApiType;

/**
 * Pre-ship check: every ability in a card-script folder must parse the way the
 * engine will parse it in a game.
 *
 * Forge builds most abilities lazily -- a trigger's Execute$ when it fires, a
 * static's AddAbility$ when it applies -- so a malformed script is invisible
 * until a player hits it, and then it usually ends the match (Way of the
 * Pyromancer, prod 2026-09-25: an SVar without "AB$" killed games at turn 13).
 * This walks the scripts that are about to ship and parses every ability with
 * Forge's own parser (AbilityFactory.getMapParams / getRecordType /
 * ApiType.smartValueOf): each A: line, and every SVar that a T: (Execute$),
 * S: (AddAbility$ / AddTrigger$ / AddStaticAbility$), R: (ReplaceWith$) or an
 * ability (SubAbility$ / Execute$) points at.
 *
 *   java -cp forge.jar forge.sim.CardScriptCheck [--allow file] dir [dir...]
 *
 * Prints one "ERROR <file>: ..." line per problem and a summary line, and exits
 * 1 when there is any ERROR. A reference to an SVar that does not exist is a
 * WARN: the engine skips those without crashing (TriggerHandler aborts the
 * trigger, AbilityFactory drops the sub-ability). --allow names a file of
 * card-file basenames (one per line, # comments) whose errors are reported as
 * ALLOWED instead, for upstream scripts known broken and not in any pool.
 */
public final class CardScriptCheck {
    private CardScriptCheck() {}

    private static final String[] ABILITY_REFS = {"SubAbility", "Execute", "ReplaceWith"};

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> allowed = new ArrayList<>();
    private int files;
    private int abilities;

    public static void main(String[] args) throws IOException {
        Set<String> allow = new HashSet<>();
        List<Path> dirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--allow".equals(args[i]) && i + 1 < args.length) {
                for (String line : Files.readAllLines(Paths.get(args[++i]), StandardCharsets.UTF_8)) {
                    line = line.replaceAll("#.*", "").trim();
                    if (!line.isEmpty()) allow.add(line);
                }
            } else {
                dirs.add(Paths.get(args[i]));
            }
        }
        if (dirs.isEmpty()) {
            System.err.println("usage: CardScriptCheck [--allow file] dir [dir...]");
            System.exit(2);
        }
        CardScriptCheck c = new CardScriptCheck();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                System.err.println("CardScriptCheck: not a directory: " + dir);
                System.exit(2);
            }
            List<Path> scripts = new ArrayList<>();
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".txt")).forEach(scripts::add);
            }
            for (Path p : scripts) {
                c.checkFile(p, allow.contains(p.getFileName().toString()));
            }
        }
        c.warnings.forEach(System.out::println);
        c.allowed.forEach(System.out::println);
        c.errors.forEach(System.out::println);
        System.out.println("CARD_SCRIPT_CHECK files=" + c.files + " abilities=" + c.abilities
                + " errors=" + c.errors.size() + " warnings=" + c.warnings.size()
                + " allowed=" + c.allowed.size());
        System.exit(c.errors.isEmpty() ? 0 : 1);
    }

    // ── one file ───────────────────────────────────────────────────────────

    private String file;
    private boolean fileAllowed;
    private Map<String, String> svars;      // this face first, then the whole file
    private Map<String, String> allSvars;

    void checkFile(Path p, boolean allowedFile) throws IOException {
        files++;
        file = p.getFileName().toString();
        fileAllowed = allowedFile;
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);

        // Faces: ALTERNATE and SPECIALIZE:<color> start a new one. SVars are
        // per face, but a lookup falls back to the whole file so a face that
        // borrows its sibling's SVar is not a false alarm.
        List<List<String>> faces = new ArrayList<>();
        faces.add(new ArrayList<>());
        allSvars = new HashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals("ALTERNATE") || line.startsWith("SPECIALIZE:")) {
                faces.add(new ArrayList<>());
                continue;
            }
            faces.get(faces.size() - 1).add(line);
            if (line.startsWith("SVar:")) {
                String[] kv = svarKv(line);
                if (kv != null) allSvars.putIfAbsent(kv[0], kv[1]);
            }
        }
        for (List<String> face : faces) {
            svars = new HashMap<>();
            for (String line : face) {
                if (line.startsWith("SVar:")) {
                    String[] kv = svarKv(line);
                    if (kv != null) svars.put(kv[0], kv[1]);
                }
            }
            for (String line : face) {
                if (line.startsWith("A:")) {
                    checkAbility(line.substring(2), "A:", new HashSet<>());
                } else if (line.startsWith("T:")) {
                    Map<String, String> m = params(line.substring(2), "T:");
                    if (m != null) refAbility(m.get("Execute"), "T: Execute$", new HashSet<>());
                } else if (line.startsWith("S:")) {
                    Map<String, String> m = params(line.substring(2), "S:");
                    if (m != null) {
                        for (String name : list(m.get("AddAbility"))) {
                            refAbility(name, "S: AddAbility$", new HashSet<>());
                        }
                        for (String name : list(m.get("AddTrigger"))) {
                            refModal(name, "S: AddTrigger$");
                        }
                        for (String name : list(m.get("AddStaticAbility"))) {
                            refModal(name, "S: AddStaticAbility$");
                        }
                    }
                } else if (line.startsWith("R:")) {
                    Map<String, String> m = params(line.substring(2), "R:");
                    if (m != null) refAbility(m.get("ReplaceWith"), "R: ReplaceWith$", new HashSet<>());
                }
            }
        }
    }

    private static String[] svarKv(String line) {
        String rest = line.substring(5);
        int colon = rest.indexOf(':');
        if (colon <= 0) return null;
        return new String[] {rest.substring(0, colon), rest.substring(colon + 1)};
    }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        for (String s : v.split("&")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String svar(String name) {
        String v = svars.get(name);
        return v != null ? v : allSvars.get(name);
    }

    private Map<String, String> params(String text, String where) {
        try {
            return AbilityFactory.getMapParams(text);
        } catch (RuntimeException e) {
            error(where + " does not parse: " + e.getMessage() + " -- " + clip(text));
            return null;
        }
    }

    /** An SVar that must hold an ability (AB$ / SP$ / DB$ / ST$). */
    private void refAbility(String name, String where, Set<String> seen) {
        if (name == null || name.isEmpty()) return;
        String v = svar(name);
        if (v == null) {
            warn(where + " " + name + " names an SVar that does not exist");
            return;
        }
        if (seen.add(name)) {
            checkAbility(v, where + " " + name, seen);
        }
    }

    /** An SVar that must hold a trigger or static (it needs a Mode$). */
    private void refModal(String name, String where) {
        String v = svar(name);
        if (v == null) {
            warn(where + " " + name + " names an SVar that does not exist");
            return;
        }
        Map<String, String> m = params(v, where + " " + name);
        if (m == null) return;
        if (!m.containsKey("Mode")) {
            error(where + " " + name + " has no Mode$ -- " + clip(v));
            return;
        }
        refAbility(m.get("Execute"), where + " " + name + " Execute$", new HashSet<>());
    }

    private void checkAbility(String text, String where, Set<String> seen) {
        abilities++;
        Map<String, String> m = params(text, where);
        if (m == null) return;
        AbilityRecordType type = AbilityRecordType.getRecordType(m);
        if (type == null) {
            error(where + " is not an ability (no AB$/SP$/DB$/ST$) -- " + clip(text));
            return;
        }
        String api = m.get(type.getPrefix());
        try {
            ApiType.smartValueOf(api);
        } catch (RuntimeException e) {
            error(where + " uses an unknown API \"" + api + "\" -- " + clip(text));
            return;
        }
        for (String key : ABILITY_REFS) {
            refAbility(m.get(key), where + " " + key + "$", seen);
        }
    }

    private void error(String msg) {
        (fileAllowed ? allowed : errors).add((fileAllowed ? "ALLOWED " : "ERROR ") + file + ": " + msg);
    }

    private void warn(String msg) {
        warnings.add("WARN " + file + ": " + msg);
    }

    private static String clip(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
