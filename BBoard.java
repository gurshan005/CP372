import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BBoard {

    // -------------------- Error Categories (for RFC) --------------------
    // INVALID_FORMAT: command syntax/arity wrong
    // OUT_OF_BOUNDS: note placement outside board
    // INVALID_COLOR: color not in allowed set
    // OVERLAP_ERROR: complete overlap (including exact same rectangle) with existing note
    // PIN_MISS: PIN hits no notes
    // NO_PIN: UNPIN where no pin exists
    // SERVER_ERROR: unexpected server-side failure (should be rare)
    enum ErrCat {
        INVALID_FORMAT,
        OUT_OF_BOUNDS,
        INVALID_COLOR,
        OVERLAP_ERROR,
        PIN_MISS,
        NO_PIN,
        SERVER_ERROR
    }

    // -------------------- Models --------------------
    static final class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
    }

    static final class Note {
        final int id;
        final int x, y;
        final String color;    // stored normalized as upper-case
        final String message;  // raw message (may contain spaces)
        final Instant createdAt;

        Note(int id, int x, int y, String color, String message) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.color = color;
            this.message = message;
            this.createdAt = Instant.now();
        }
    }

    // -------------------- Shared Board State (thread-safe via locks) --------------------
    static final class BoardState {
        final int boardW, boardH, noteW, noteH;
        final Set<String> validColorsUpper;
        final AtomicInteger nextId = new AtomicInteger(1);

        // Notes are non-persistent: in-memory only
        final Map<Integer, Note> notesById = new HashMap<>();

        // Pins: a coordinate may have at most one pin (Set)
        final Set<Point> pins = new HashSet<>();

        // Synchronization: allow concurrent GETs; POST/PIN/UNPIN/SHAKE/CLEAR are atomic under write lock
        final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

        BoardState(int boardW, int boardH, int noteW, int noteH, List<String> colors) {
            this.boardW = boardW;
            this.boardH = boardH;
            this.noteW = noteW;
            this.noteH = noteH;
            this.validColorsUpper = new HashSet<>();
            for (String c : colors) validColorsUpper.add(c.toUpperCase(Locale.ROOT));
        }

        // Geometry: point inside note rectangle (half-open intervals)
        boolean containsPoint(Note n, int px, int py) {
            return px >= n.x && px < n.x + noteW && py >= n.y && py < n.y + noteH;
        }

        // Rule: note must lie completely inside board
        boolean insideBoard(int x, int y) {
            return x >= 0 && y >= 0 && x + noteW <= boardW && y + noteH <= boardH;
        }

        // Rectangle containment: A contains B (including equal edges)
        static boolean containsRect(int ax, int ay, int aw, int ah,
                                    int bx, int by, int bw, int bh) {
            return bx >= ax && by >= ay &&
                   bx + bw <= ax + aw &&
                   by + bh <= ay + ah;
        }

        // Complete overlap rule: new note must NOT completely overlap an existing note,
        // and existing must NOT completely overlap new note.
        // With fixed note size, this effectively forbids exact same (x,y), but we implement general containment anyway.
        void assertNoCompleteOverlap(int nx, int ny) {
            int nw = noteW, nh = noteH;
            for (Note ex : notesById.values()) {
                boolean newContainsOld = containsRect(nx, ny, nw, nh, ex.x, ex.y, nw, nh);
                boolean oldContainsNew = containsRect(ex.x, ex.y, nw, nh, nx, ny, nw, nh);
                if (newContainsOld || oldContainsNew) {
                    throw new ProtoException(ErrCat.OVERLAP_ERROR,
                            "Complete overlap not allowed with note id=" + ex.id);
                }
            }
        }

        // Pinned status is DERIVED: a note is pinned if it contains >=1 pin coordinate
        boolean isPinned(Note n) {
            for (Point p : pins) {
                if (containsPoint(n, p.x, p.y)) return true;
            }
            return false;
        }

        // POST: add new note after validations
        int post(int x, int y, String colorUpper, String message) {
            // Called under write lock
            if (!validColorsUpper.contains(colorUpper)) {
                throw new ProtoException(ErrCat.INVALID_COLOR, "Invalid color: " + colorUpper);
            }
            if (!insideBoard(x, y)) {
                throw new ProtoException(ErrCat.OUT_OF_BOUNDS, "Note out of bounds");
            }
            assertNoCompleteOverlap(x, y);

            int id = nextId.getAndIncrement();
            notesById.put(id, new Note(id, x, y, colorUpper, message));
            return id;
        }

        // PIN: place a pin coordinate and pin all notes containing it
        // If pin hits no notes => ERROR
        void pin(int x, int y) {
            // Called under write lock
            boolean hit = false;
            for (Note n : notesById.values()) {
                if (containsPoint(n, x, y)) { hit = true; }
            }
            if (!hit) {
                throw new ProtoException(ErrCat.PIN_MISS, "PIN hit no notes at (" + x + "," + y + ")");
            }
            pins.add(new Point(x, y)); // idempotent if already present
        }

        // UNPIN: removes one pin at coordinate. If none => ERROR
        void unpin(int x, int y) {
            // Called under write lock
            boolean removed = pins.remove(new Point(x, y));
            if (!removed) {
                throw new ProtoException(ErrCat.NO_PIN, "No pin at (" + x + "," + y + ")");
            }
            // Notes' pinned state auto-updates because derived from pins set
        }

        // SHAKE: removes all unpinned notes (atomic)
        int shakeRemoveUnpinned() {
            // Called under write lock
            int removed = 0;
            Iterator<Map.Entry<Integer, Note>> it = notesById.entrySet().iterator();
            while (it.hasNext()) {
                Note n = it.next().getValue();
                if (!isPinned(n)) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }

        // CLEAR: removes all notes and all pins
        void clearAll() {
            // Called under write lock
            notesById.clear();
            pins.clear();
        }

        // GET PINS
        List<Point> getPinsSorted() {
            // Called under read lock
            ArrayList<Point> out = new ArrayList<>(pins);
            out.sort((a, b) -> (a.y != b.y) ? Integer.compare(a.y, b.y) : Integer.compare(a.x, b.x));
            return out;
        }

        // GET with criteria: color, contains point, refersTo substring
        List<Note> getNotesFiltered(Optional<String> colorUpper,
                                    Optional<Point> contains,
                                    Optional<String> refersTo) {
            // Called under read lock
            ArrayList<Note> out = new ArrayList<>();
            String ref = refersTo.map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);

            for (Note n : notesById.values()) {
                if (colorUpper.isPresent() && !n.color.equals(colorUpper.get())) continue;
                if (contains.isPresent()) {
                    Point p = contains.get();
                    if (!containsPoint(n, p.x, p.y)) continue;
                }
                if (ref != null) {
                    if (!n.message.toLowerCase(Locale.ROOT).contains(ref)) continue;
                }
                out.add(n);
            }

            // Useful ordering for clients: pinned first, then newest
            out.sort((a, b) -> {
                boolean ap = isPinned(a);
                boolean bp = isPinned(b);
                if (ap != bp) return Boolean.compare(bp, ap);
                return Integer.compare(b.id, a.id);
            });
            return out;
        }
    }

    // Custom exception for protocol-level errors (never crash server)
    static final class ProtoException extends RuntimeException {
        final ErrCat cat;
        ProtoException(ErrCat cat, String msg) { super(msg); this.cat = cat; }
    }

    // -------------------- Server --------------------
    private final int port;
    private final BoardState state;
    private final ExecutorService pool;

    public BBoard(int port, int boardW, int boardH, int noteW, int noteH, List<String> colors, int threads) {
        this.port = port;
        this.state = new BoardState(boardW, boardH, noteW, noteH, colors);
        this.pool = Executors.newFixedThreadPool(threads);
    }

    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("BBoard server listening on port " + port);
            System.out.println("Board: " + state.boardW + "x" + state.boardH + " | Note: " + state.noteW + "x" + state.noteH);
            System.out.println("Colors: " + state.validColorsUpper);

            while (true) {
                Socket client = ss.accept();
                pool.submit(() -> handleClient(client));
            }
        }
    }

    private void handleClient(Socket client) {
        String tag = client.getRemoteSocketAddress().toString();
        System.out.println("Connected: " + tag);

        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

            // 5.1 Handshake: dimensions + colors
            writeLine(out, "BOARD " + state.boardW + " " + state.boardH);
            writeLine(out, "NOTE " + state.noteW + " " + state.noteH);
            // preserve original order? we can echo any order list; RFC just says list valid colors
            // we'll output the set sorted for determinism
            List<String> colorList = new ArrayList<>(state.validColorsUpper);
            colorList.sort(String::compareTo);
            writeLine(out, "COLORS " + String.join(" ", colorList));
            writeLine(out, "OK READY");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    if (line.equalsIgnoreCase("DISCONNECT")) {
                        writeLine(out, "OK BYE");
                        break;
                    } else if (line.regionMatches(true, 0, "POST ", 0, 5)) {
                        handlePOST(out, line);
                    } else if (line.regionMatches(true, 0, "PIN ", 0, 4)) {
                        handlePIN(out, line, true);
                    } else if (line.regionMatches(true, 0, "UNPIN ", 0, 6)) {
                        handlePIN(out, line, false);
                    } else if (line.equalsIgnoreCase("SHAKE")) {
                        handleSHAKE(out);
                    } else if (line.equalsIgnoreCase("CLEAR")) {
                        handleCLEAR(out);
                    } else if (line.regionMatches(true, 0, "GET ", 0, 4)) {
                        handleGET(out, line);
                    } else {
                        throw new ProtoException(ErrCat.INVALID_FORMAT, "Unknown command");
                    }
                } catch (ProtoException pe) {
                    writeLine(out, "ERROR " + pe.cat + " " + pe.getMessage());
                } catch (Exception e) {
                    // Never crash due to client errors; unexpected internal issues get SERVER_ERROR
                    writeLine(out, "ERROR " + ErrCat.SERVER_ERROR + " " + e.getMessage());
                }
            }

        } catch (IOException ioe) {
            System.out.println("Client IO error (" + tag + "): " + ioe.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            System.out.println("Disconnected: " + tag);
        }
    }

    // -------------------- Command Handlers --------------------

    // Example: POST 2 3 white Meeting next Wednesday from 2 to 3
    // Format: POST <x> <y> <color> <message...>
    private void handlePOST(BufferedWriter out, String line) throws IOException {
        String rest = line.substring(5).trim();
        String[] parts = rest.split("\\s+", 4); // x y color message
        if (parts.length < 4) {
            throw new ProtoException(ErrCat.INVALID_FORMAT, "Usage: POST <x> <y> <color> <message>");
        }

        int x = parseNonNegInt(parts[0], "x");
        int y = parseNonNegInt(parts[1], "y");
        String colorUpper = parts[2].toUpperCase(Locale.ROOT);
        String message = parts[3];

        state.rw.writeLock().lock();
        try {
            int id = state.post(x, y, colorUpper, message);
            writeLine(out, "OK POSTED " + id);
        } finally {
            state.rw.writeLock().unlock();
        }
    }

    // PIN/UNPIN <x> <y>
    private void handlePIN(BufferedWriter out, String line, boolean isPin) throws IOException {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3) {
            throw new ProtoException(ErrCat.INVALID_FORMAT,
                    "Usage: " + (isPin ? "PIN" : "UNPIN") + " <x> <y>");
        }
        int x = parseNonNegInt(parts[1], "x");
        int y = parseNonNegInt(parts[2], "y");

        state.rw.writeLock().lock();
        try {
            if (isPin) {
                state.pin(x, y);
                writeLine(out, "OK PINNED " + x + " " + y);
            } else {
                state.unpin(x, y);
                writeLine(out, "OK UNPINNED " + x + " " + y);
            }
        } finally {
            state.rw.writeLock().unlock();
        }
    }

    // SHAKE: Removes all unpinned notes. Atomic.
    private void handleSHAKE(BufferedWriter out) throws IOException {
        state.rw.writeLock().lock();
        try {
            int removed = state.shakeRemoveUnpinned();
            writeLine(out, "OK SHAKEN REMOVED " + removed);
        } finally {
            state.rw.writeLock().unlock();
        }
    }

    // CLEAR: Removes all notes and all pins.
    private void handleCLEAR(BufferedWriter out) throws IOException {
        state.rw.writeLock().lock();
        try {
            state.clearAll();
            writeLine(out, "OK CLEARED");
        } finally {
            state.rw.writeLock().unlock();
        }
    }

    // GET PINS
    // General GET form:
    // GET color=<color> contains=<x> <y> refersTo=<substring>
    // Missing criteria imply ALL
    private void handleGET(BufferedWriter out, String line) throws IOException {
        String rest = line.substring(4).trim();

        if (rest.equalsIgnoreCase("PINS")) {
            state.rw.readLock().lock();
            try {
                List<Point> pins = state.getPinsSorted();
                writeLine(out, "DATA BEGIN");
                for (Point p : pins) {
                    writeLine(out, "PIN " + p.x + " " + p.y);
                }
                writeLine(out, "DATA END");
            } finally {
                state.rw.readLock().unlock();
            }
            return;
        }

        // Parse criteria tokens; contains consumes two ints after "contains="
        Optional<String> color = Optional.empty();
        Optional<Point> contains = Optional.empty();
        Optional<String> refersTo = Optional.empty();

        // Tokenize by whitespace; criteria themselves are key=value
        // Example: GET contains=4 6 refersTo=Fred
        String[] tokens = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];

            if (t.regionMatches(true, 0, "color=", 0, 6)) {
                String v = t.substring(6).trim();
                if (v.isEmpty()) throw new ProtoException(ErrCat.INVALID_FORMAT, "color=<color> missing value");
                String cu = v.toUpperCase(Locale.ROOT);
                // If color invalid -> INVALID_COLOR (even though GET doesn't modify, spec says structured errors)
                if (!state.validColorsUpper.contains(cu)) {
                    throw new ProtoException(ErrCat.INVALID_COLOR, "Invalid color: " + cu);
                }
                color = Optional.of(cu);

            } else if (t.regionMatches(true, 0, "contains=", 0, 9)) {
                String after = t.substring(9).trim();
                int x, y;
                if (!after.isEmpty()) {
                    // contains=4 then next token is y (rare, but we support)
                    x = parseNonNegInt(after, "contains.x");
                    if (i + 1 >= tokens.length) throw new ProtoException(ErrCat.INVALID_FORMAT, "contains=<x> <y> missing y");
                    y = parseNonNegInt(tokens[++i], "contains.y");
                } else {
                    // contains= then x y are next tokens
                    if (i + 2 >= tokens.length) throw new ProtoException(ErrCat.INVALID_FORMAT, "contains=<x> <y> missing coords");
                    x = parseNonNegInt(tokens[++i], "contains.x");
                    y = parseNonNegInt(tokens[++i], "contains.y");
                }
                contains = Optional.of(new Point(x, y));

            } else if (t.regionMatches(true, 0, "refersTo=", 0, 8)) {
                String v = t.substring(8).trim();
                if (v.isEmpty()) throw new ProtoException(ErrCat.INVALID_FORMAT, "refersTo=<substring> missing value");
                refersTo = Optional.of(v);

            } else {
                throw new ProtoException(ErrCat.INVALID_FORMAT, "Unknown GET criterion: " + t);
            }
        }

        state.rw.readLock().lock();
        try {
            List<Note> notes = state.getNotesFiltered(color, contains, refersTo);
            writeLine(out, "DATA BEGIN");
            for (Note n : notes) {
                String pinned = state.isPinned(n) ? "PINNED" : "UNPINNED";
                // Structured note line; easy for clients to parse
                writeLine(out, "NOTE " + n.id + " " + n.x + " " + n.y + " " + n.color + " " + pinned + " " + n.message);
            }
            writeLine(out, "DATA END");
        } finally {
            state.rw.readLock().unlock();
        }
    }

    // -------------------- Utilities --------------------
    private static int parseNonNegInt(String s, String field) {
        int v;
        try { v = Integer.parseInt(s); }
        catch (NumberFormatException e) { throw new ProtoException(ErrCat.INVALID_FORMAT, "Invalid integer for " + field); }
        if (v < 0) throw new ProtoException(ErrCat.INVALID_FORMAT, "Negative value for " + field);
        return v;
    }

    private static void writeLine(BufferedWriter out, String msg) throws IOException {
        out.write(msg);
        out.write("\n");
        out.flush();
    }

    // -------------------- Main (args exactly as spec) --------------------
    // java BBoard <port> <board_width> <board_height> <note_width> <note_height> <color1> ... <colorN>
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Usage: java BBoard <port> <board_width> <board_height> <note_width> <note_height> <color1> ... <colorN>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        int boardW = Integer.parseInt(args[1]);
        int boardH = Integer.parseInt(args[2]);
        int noteW  = Integer.parseInt(args[3]);
        int noteH  = Integer.parseInt(args[4]);

        if (port <= 0 || boardW <= 0 || boardH <= 0 || noteW <= 0 || noteH <= 0) {
            System.err.println("ERROR: port and dimensions must be positive integers.");
            System.exit(1);
        }

        List<String> colors = new ArrayList<>();
        for (int i = 5; i < args.length; i++) colors.add(args[i]);

        // Thread count: you can tune; not specified as arg, so choose a sensible default.
        int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

        new BBoard(port, boardW, boardH, noteW, noteH, colors, threads).start();
    }
}
