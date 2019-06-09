/*
 * Copyright 2017 HomeAdvisor, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.homeadvisor.kafdrop.service;

import com.fasterxml.jackson.databind.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.homeadvisor.kafdrop.model.*;
import com.homeadvisor.kafdrop.util.*;
import kafka.api.*;
import kafka.cluster.*;
import kafka.common.*;
import kafka.javaapi.ConsumerMetadataResponse;
import kafka.javaapi.OffsetFetchRequest;
import kafka.javaapi.OffsetFetchResponse;
import kafka.javaapi.OffsetRequest;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.javaapi.TopicMetadata;
import kafka.network.*;
import kafka.utils.*;
import org.apache.commons.lang3.*;
import org.apache.curator.framework.*;
import org.apache.curator.framework.recipes.cache.*;
import org.apache.curator.framework.recipes.cache.PathChildrenCache.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.retry.backoff.*;
import org.springframework.retry.policy.*;
import org.springframework.retry.support.*;
import org.springframework.stereotype.*;

import javax.annotation.*;
import java.io.*;
import java.util.Objects;
import java.util.Optional;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

@Service
public class CuratorKafkaMonitor implements KafkaMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(CuratorKafkaMonitor.class);

  @Autowired
  private CuratorFramework curatorFramework;

  @Autowired
  private ObjectMapper objectMapper;

  private PathChildrenCache brokerPathCache;
  private PathChildrenCache topicConfigPathCache;
  private TreeCache consumerTreeCache;
  private NodeCache controllerNodeCache;

  private Map<Integer, BrokerVO> brokerCache = new TreeMap<>();

  private AtomicInteger cacheInitCounter = new AtomicInteger();

  private ForkJoinPool threadPool;

  @Autowired
  private CuratorKafkaMonitorProperties properties;
  @Autowired
  private KafkaHighLevelConsumer kafkaHighLevelConsumer;

  private RetryTemplate retryTemplate;

  @PostConstruct
  public void start() throws Exception {
    threadPool = new ForkJoinPool(properties.getThreadPoolSize());

    FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
    backOffPolicy.setBackOffPeriod(properties.getRetry().getBackoffMillis());

    final SimpleRetryPolicy retryPolicy =
        new SimpleRetryPolicy(properties.getRetry().getMaxAttempts(),
                              ImmutableMap.of(InterruptedException.class, false,
                                              Exception.class, true));

    retryTemplate = new RetryTemplate();
    retryTemplate.setBackOffPolicy(backOffPolicy);
    retryTemplate.setRetryPolicy(retryPolicy);

    cacheInitCounter.set(4);

    brokerPathCache = new PathChildrenCache(curatorFramework, ZkUtils.BrokerIdsPath(), true);
    brokerPathCache.getListenable().addListener(new BrokerListener());
    brokerPathCache.getListenable().addListener((f, e) -> {
      if (e.getType() == PathChildrenCacheEvent.Type.INITIALIZED) {
        cacheInitCounter.decrementAndGet();
        LOG.info("Broker cache initialized");
      }
    });
    brokerPathCache.start(StartMode.POST_INITIALIZED_EVENT);

    topicConfigPathCache = new PathChildrenCache(curatorFramework, ZkUtils.TopicConfigPath(), true);
    topicConfigPathCache.getListenable().addListener((f, e) -> {
      if (e.getType() == PathChildrenCacheEvent.Type.INITIALIZED) {
        cacheInitCounter.decrementAndGet();
        LOG.info("Topic configuration cache initialized");
      }
    });
    topicConfigPathCache.start(StartMode.POST_INITIALIZED_EVENT);

    final TreeCache topicTreeCache = new TreeCache(curatorFramework, ZkUtils.BrokerTopicsPath());
    topicTreeCache.getListenable().addListener((client, event) -> {
      if (event.getType() == TreeCacheEvent.Type.INITIALIZED) {
        cacheInitCounter.decrementAndGet();
        LOG.info("Topic tree cache initialized");
      }
    });
    topicTreeCache.start();

    consumerTreeCache = new TreeCache(curatorFramework, ZkUtils.ConsumersPath());
    consumerTreeCache.getListenable().addListener((client, event) -> {
      if (event.getType() == TreeCacheEvent.Type.INITIALIZED) {
        cacheInitCounter.decrementAndGet();
        LOG.info("Consumer tree cache initialized");
      }
    });
    consumerTreeCache.start();

    controllerNodeCache = new NodeCache(curatorFramework, ZkUtils.ControllerPath());
    controllerNodeCache.getListenable().addListener(this::updateController);
    controllerNodeCache.start(true);
    updateController();
  }

  private String clientId() {
    return properties.getClientId();
  }

  private void updateController() {
    Optional.ofNullable(controllerNodeCache.getCurrentData())
        .map(data -> {
          try {
            Map controllerData = objectMapper.reader(Map.class).readValue(data.getData());
            return (Integer) controllerData.get("brokerid");
          } catch (IOException e) {
            LOG.error("Unable to read controller data", e);
            return null;
          }
        })
        .ifPresent(this::updateController);
  }

  private void updateController(int brokerId) {
    brokerCache.values()
        .forEach(broker -> broker.setController(broker.getId() == brokerId));
  }

  private void validateInitialized() {
    if (cacheInitCounter.get() > 0) {
      throw new NotInitializedException();
    }
  }

  @PreDestroy
  public void stop()
  throws IOException {
    consumerTreeCache.close();
    topicConfigPathCache.close();
    brokerPathCache.close();
    controllerNodeCache.close();
  }

  private int brokerId(ChildData input) {
    return Integer.parseInt(StringUtils.substringAfter(input.getPath(), ZkUtils.BrokerIdsPath() + "/"));
  }

  private BrokerVO addBroker(BrokerVO broker) {
    final BrokerVO oldBroker = brokerCache.put(broker.getId(), broker);
    LOG.info("Kafka broker {} was {}", broker.getId(), oldBroker == null ? "added" : "updated");
    return oldBroker;
  }

  private BrokerVO removeBroker(int brokerId) {
    final BrokerVO broker = brokerCache.remove(brokerId);
    LOG.info("Kafka broker {} was removed", broker.getId());
    return broker;
  }

  @Override
  public List<BrokerVO> getBrokers() {
    validateInitialized();
    return new ArrayList<>(brokerCache.values());
  }

  @Override
  public Optional<BrokerVO> getBroker(int id) {
    validateInitialized();
    return Optional.ofNullable(brokerCache.get(id));
  }

  private BrokerChannel brokerChannel(Integer brokerId) {
    if (brokerId == null) {
      brokerId = randomBroker();
      if (brokerId == null) {
        throw new BrokerNotFoundException("No brokers available to select from");
      }
    }

    Integer finalBrokerId = brokerId;
    BrokerVO broker = getBroker(brokerId)
        .orElseThrow(() -> new BrokerNotFoundException("Broker " + finalBrokerId + " is not available"));

    return BrokerChannel.forBroker(broker.getHost(), broker.getPort());
  }

  private Integer randomBroker() {
    if (brokerCache.size() > 0) {
      List<Integer> brokerIds = new ArrayList<>(brokerCache.keySet());
      Collections.shuffle(brokerIds);
      return brokerIds.get(0);
    } else {
      return null;
    }
  }

  @Override
  public ClusterSummaryVO getClusterSummary(Collection<TopicVO> topics) {
    final ClusterSummaryVO topicSummary = topics.stream()
        .map(topic -> {
          ClusterSummaryVO summary = new ClusterSummaryVO();
          summary.setPartitionCount(topic.getPartitions().size());
          summary.setUnderReplicatedCount(topic.getUnderReplicatedPartitions().size());
          summary.setPreferredReplicaPercent(topic.getPreferredReplicaPercent());
          topic.getPartitions()
              .forEach(partition -> {
                if (partition.getLeader() != null) {
                  summary.addBrokerLeaderPartition(partition.getLeader().getId());
                }
                if (partition.getPreferredLeader() != null) {
                  summary.addBrokerPreferredLeaderPartition(partition.getPreferredLeader().getId());
                }
                partition.getReplicas()
                    .forEach(replica -> summary.addExpectedBrokerId(replica.getId()));
              });
          return summary;
        })
        .reduce((s1, s2) -> {
          s1.setPartitionCount(s1.getPartitionCount() + s2.getPartitionCount());
          s1.setUnderReplicatedCount(s1.getUnderReplicatedCount() + s2.getUnderReplicatedCount());
          s1.setPreferredReplicaPercent(s1.getPreferredReplicaPercent() + s2.getPreferredReplicaPercent());
          s2.getBrokerLeaderPartitionCount().forEach(s1::addBrokerLeaderPartition);
          s2.getBrokerPreferredLeaderPartitionCount().forEach(s1::addBrokerPreferredLeaderPartition);
          return s1;
        })
        .orElseGet(ClusterSummaryVO::new);
    topicSummary.setTopicCount(topics.size());
    topicSummary.setPreferredReplicaPercent(topicSummary.getPreferredReplicaPercent() / topics.size());
    return topicSummary;
  }

  @Override
  public List<TopicVO> getTopics() {
    validateInitialized();
    return getTopicMetadata().values().stream()
        .sorted(Comparator.comparing(TopicVO::getName))
        .collect(Collectors.toList());
  }

  @Override
  public Optional<TopicVO> getTopic(String topic) {
    validateInitialized();
    final Optional<TopicVO> topicVO = Optional.ofNullable(getTopicMetadata(topic).get(topic));
    topicVO.ifPresent(vo -> vo.setPartitions(getTopicPartitionSizes(vo)));
    return topicVO;
  }

  private Map<String, TopicVO> getTopicMetadata(String... topics) {
    return retryTemplate.execute(
        context -> brokerChannel(null)
            .execute(channel -> getTopicMetadata(channel, topics)));
  }

  private Map<String, TopicVO> getTopicMetadata(BlockingChannel channel, String... topics) {
    return kafkaHighLevelConsumer.getTopicsInfo(topics);
  }

  @Override
  public List<MessageVO> getMessages(TopicPartition topicPartition, long offset, long count,
                                     MessageDeserializer deserializer) {
    List<ConsumerRecord<String, String>> records = kafkaHighLevelConsumer.getLatestRecords(topicPartition, offset, count, deserializer);
    if (records != null) {
      List<MessageVO> messageVos = Lists.newArrayList();
      for (ConsumerRecord<String, String> record : records) {
        MessageVO messageVo = new MessageVO();
        messageVo.setKey(record.key());
        messageVo.setMessage(record.value());
        messageVo.setHeaders(Arrays.toString(record.headers().toArray()));

        messageVos.add(messageVo);
      }
      return messageVos;
    } else {
      return Collections.emptyList();
    }
  }

  private Map<Integer, TopicPartitionVO> getTopicPartitionSizes(TopicVO topic) {
    return kafkaHighLevelConsumer.getPartitionSize(topic.getName());
  }

  private class BrokerListener implements PathChildrenCacheListener {
    @Override
    public void childEvent(CuratorFramework framework, PathChildrenCacheEvent event) {
      switch (event.getType()) {
        case CHILD_REMOVED: {
          removeBroker(brokerId(event.getData()));
          break;
        }

        case CHILD_ADDED:
        case CHILD_UPDATED: {
          addBroker(parseBroker(event.getData()));
          break;
        }

        case INITIALIZED: {
          brokerPathCache.getCurrentData().stream()
              .map(BrokerListener.this::parseBroker)
              .forEach(CuratorKafkaMonitor.this::addBroker);
          break;
        }
      }
      updateController();
    }

    private int brokerId(ChildData input) {
      return Integer.parseInt(StringUtils.substringAfter(input.getPath(), ZkUtils.BrokerIdsPath() + "/"));
    }

    private BrokerVO parseBroker(ChildData input) {
      try {
        final BrokerVO broker = objectMapper.reader(BrokerVO.class).readValue(input.getData());
        broker.setId(brokerId(input));
        return broker;
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  }
}
