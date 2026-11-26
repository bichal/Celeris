import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unused")
class NetworkGraphPayloadTest {

    @Test
    void newSegmentsReceiveAFreshPayloadFromTheSupplier() {
        AtomicInteger counter = new AtomicInteger();
        SegmentedGraphImpl<Integer> graph = GraphTestSupport.newGraph(counter::incrementAndGet);

        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        GraphSegment<Integer> segment = graph.segments().iterator().next();
        assertEquals(1, segment.payload());
    }

    @Test
    void rebuildingAffectedSegmentsAllocatesNewPayloadsForEach() {
        AtomicInteger counter = new AtomicInteger();
        SegmentedGraphImpl<Integer> graph = GraphTestSupport.newGraph(counter::incrementAndGet);

        NodeId start = GraphTestSupport.pos(0, 0, 0);
        NodeId mid = GraphTestSupport.relative(start, GraphTestSupport.EAST, 5);

        GraphTestSupport.addLineWithJunction(graph, start, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));

        int payloadsSoFar = counter.get();

        graph.addNode(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST, GraphTestSupport.SOUTH));

        assertEquals(payloadsSoFar + 2, counter.get(),
                "Splitting one segment into two should allocate exactly two new payloads");
    }
}
