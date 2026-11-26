import net.bichal.mods.celeris.core.topology.ConnectionMask;
import net.bichal.mods.celeris.core.topology.NetworkTopologyImpl;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;

@SuppressWarnings("unused")
final class GraphTestSupport {

    // Direction ordinal order, matching BranchlessTables: 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST.
    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    private static final int[] OPPOSITE = {UP, DOWN, SOUTH, NORTH, EAST, WEST};

    private GraphTestSupport() {
    }

    static SegmentedGraphImpl<String> newGraph() {
        return new SegmentedGraphImpl<>(new NetworkTopologyImpl(), () -> "");
    }

    static <T> SegmentedGraphImpl<T> newGraph(java.util.function.Supplier<T> payload) {
        return new SegmentedGraphImpl<>(new NetworkTopologyImpl(), payload);
    }

    static NodeId pos(int x, int y, int z) {
        return NodeId.pack(x, y, z);
    }

    static NodeId relative(NodeId from, int direction, int count) {
        NodeId result = from;
        for (int i = 0; i < count; i++) {
            result = result.step(direction);
        }
        return result;
    }

    static NodeId relative(NodeId from, int direction) {
        return relative(from, direction, 1);
    }

    static int opposite(int direction) {
        return OPPOSITE[direction];
    }

    static ConnectionMask dirs(int... directions) {
        int bits = 0;
        for (int d : directions) {
            bits |= 1 << d;
        }
        return ConnectionMask.of(bits);
    }

    static void addStraightLine(SegmentedGraphImpl<?> graph, NodeId start, int axisDirection, int blockCount) {
        int opposite = opposite(axisDirection);
        for (int i = 0; i < blockCount; i++) {
            NodeId pos = relative(start, axisDirection, i);
            boolean isFirst = i == 0;
            boolean isLast = i == blockCount - 1;

            if (isFirst && isLast) {
                graph.addNode(pos, dirs());
            } else if (isFirst) {
                graph.addNode(pos, dirs(axisDirection));
            } else if (isLast) {
                graph.addNode(pos, dirs(opposite));
            } else {
                graph.addNode(pos, dirs(axisDirection, opposite));
            }
        }
    }

    /**
     * Ten blocks east of {@code start}: both ends turn north, the straight blocks go in first,
     * then block 5 with {@code junction}'s connections, then the far end.
     */
    static void addLineWithJunction(SegmentedGraphImpl<?> graph, NodeId start, ConnectionMask junction) {
        graph.addNode(start, dirs(EAST, NORTH));
        for (int i = 1; i < 10; i++) {
            if (i != 5) {
                graph.addNode(relative(start, EAST, i), dirs(EAST, WEST));
            }
        }
        graph.addNode(relative(start, EAST, 5), junction);
        graph.addNode(relative(start, EAST, 10), dirs(WEST, NORTH));
    }

    static GraphSegment<String> onlySegment(SegmentedGraphImpl<String> graph) {
        var segments = graph.segments();
        if (segments.size() != 1) {
            throw new AssertionError("Expected exactly 1 segment, found " + segments.size());
        }
        return segments.iterator().next();
    }
}
