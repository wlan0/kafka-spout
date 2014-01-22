package nl.minvenj.nfi.storm.kafka;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import backtype.storm.metric.api.AssignableMetric;
import backtype.storm.metric.api.CountMetric;
import backtype.storm.spout.SpoutOutputCollector;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;
import nl.minvenj.nfi.storm.kafka.util.KafkaMessageId;

public class KafkaSpoutMetricsTest {
    private KafkaSpout _subject;

    @Before
    public void setup() {
        _subject = new KafkaSpout();
        _subject._topic = "test-topic";
        _subject._bufSize = 4;

        // add three queued messages (4, 6 and 8 bytes long)
        final KafkaMessageId id1 = new KafkaMessageId(1, 1234);
        _subject._queue.add(id1);
        _subject._inProgress.put(id1, new byte[]{1, 2, 3, 4});
        final KafkaMessageId id2 = new KafkaMessageId(1, 123);
        _subject._queue.add(id2);
        _subject._inProgress.put(id2, new byte[]{1, 2, 3, 4, 5, 6});
        final KafkaMessageId id3 = new KafkaMessageId(2, 12345);
        _subject._queue.add(id3);
        _subject._inProgress.put(id3, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        // make sure a collector is present
        _subject._collector = mock(SpoutOutputCollector.class);

        final Map<String, List<KafkaStream<byte[], byte[]>>> stream = new HashMap<String, List<KafkaStream<byte[], byte[]>>>() {{
            final KafkaStream<byte[], byte[]> mockedStream = mock(KafkaStream.class);
            final ConsumerIterator<byte[], byte[]> iterator = mock(ConsumerIterator.class);
            // make the iterator indicate a next message available once
            when(iterator.hasNext()).thenReturn(true);
            when(iterator.next()).thenReturn(new MessageAndMetadata<byte[], byte[]>(
                new byte[]{},
                new byte[]{1, 2, 3, 4},
                "test-topic",
                1,
                1234
            )).thenThrow(ConsumerTimeoutException.class);
            when(mockedStream.iterator()).thenReturn(iterator);
            put("test-topic", Arrays.asList(mockedStream));
        }};
        _subject._consumer = mock(ConsumerConnector.class);
        when(_subject._consumer.createMessageStreams(any(Map.class))).thenReturn(stream);
    }

    @Test
    public void testBufferLoadMetric() {
        _subject._bufferLoadMetric = new AssignableMetric(0.0);
        _subject._queue.clear();
        _subject._inProgress.clear();

        final double originalLoad = (Double) _subject._bufferLoadMetric.getValueAndReset();
        assertEquals(originalLoad, 0.0, 0.01);
        // stream contains a single message, buffer size is 4, load should be 0.25
        _subject.fillBuffer();
        assertEquals(0.25, (Double) _subject._bufferLoadMetric.getValueAndReset(), 0.01);

        _subject._inProgress.clear();
        _subject._queue.clear();
        // refill buffer, without messages in the stream should yield a load of 0.0
        _subject.fillBuffer();
        assertEquals(0.0, (Double) _subject._bufferLoadMetric.getValueAndReset(), 0.01);
    }

    @Test
    public void testTotalBytesMetric() {
        _subject._emittedBytesMetric = new CountMetric();
        final long originalTotal = (Long) _subject._emittedBytesMetric.getValueAndReset();
        assertEquals(originalTotal, 0);

        // emit a single 4-byte message queued and verify the counter has been incremented
        _subject.nextTuple();
        assertEquals(4, ((Long) _subject._emittedBytesMetric.getValueAndReset()).longValue());

        // emit the rest of the messages and verify the counter acts as a sum
        _subject.nextTuple();
        _subject.nextTuple();
        assertEquals(14, ((Long) _subject._emittedBytesMetric.getValueAndReset()).longValue());
    }
}