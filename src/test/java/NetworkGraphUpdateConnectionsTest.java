import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class NetworkGraphUpdateConnectionsTest {

    @Test
    void updateConnectionsOnUnknownPositionRegistersItAsANewNode() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.updateConnections(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertEquals(1, graph.segments().size());
    }

    @Test
    void turningAJunctionIntoAStraightThroughMergesItsTwoSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        NodeId mid = GraphTestSupport.relative(start, GraphTestSupport.EAST, 5);

        GraphTestSupport.addLineWithJunction(graph, start, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST, GraphTestSupport.SOUTH));

        assertEquals(2, graph.segments().size());

        graph.updateConnections(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));

        assertEquals(1, graph.segments().size());
        assertEquals(10, GraphTestSupport.onlySegment(graph).length());
    }

    @Test
    void removingAllConnectionsFromAJunctionDeletesItsSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));
        assertEquals(1, graph.segments().size());

        graph.updateConnections(a, GraphTestSupport.dirs());

        assertTrue(graph.segments().isEmpty());
    }
}
