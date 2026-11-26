import net.bichal.mods.celeris.core.concurrency.MpscRingBuffer;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unused")
class RingBufferSmokeTest {

    @Test
    void mpscRingBufferHandlesConcurrentProducers() throws Exception {
        int producers = 8;
        int itemsPerProducer = 150_000;
        try (MpscRingBuffer ring = new MpscRingBuffer(1 << 6, Long.BYTES)) {
            MpscStress.Result result = MpscStress.run(producers, itemsPerProducer, 5_000,
                    (p, i) -> ring.tryPublish(MpscStress.encode(p, i)),
                    sink -> {
                        long encoded = ring.tryConsume(-1L);
                        if (encoded == -1L) {
                            return 0;
                        }
                        sink.acceptEncoded(encoded);
                        return 1;
                    });
            result.assertExact(producers, itemsPerProducer, "");
            result.print("MpscRingBuffer (" + producers + " producers)");
        }
    }
}
