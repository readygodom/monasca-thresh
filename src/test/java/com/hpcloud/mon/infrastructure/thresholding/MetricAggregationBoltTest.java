/*
 * Copyright (c) 2014 Hewlett-Packard Development Company, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hpcloud.mon.infrastructure.thresholding;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import backtype.storm.Constants;
import backtype.storm.Testing;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.testing.MkTupleParam;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import com.hpcloud.mon.common.model.alarm.AlarmOperator;
import com.hpcloud.mon.common.model.alarm.AlarmState;
import com.hpcloud.mon.common.model.alarm.AlarmSubExpression;
import com.hpcloud.mon.common.model.metric.Metric;
import com.hpcloud.mon.common.model.metric.MetricDefinition;
import com.hpcloud.mon.domain.model.MetricDefinitionAndTenantId;
import com.hpcloud.mon.domain.model.SubAlarm;
import com.hpcloud.mon.domain.model.SubAlarmStats;
import com.hpcloud.mon.domain.service.SubAlarmDAO;
import com.hpcloud.mon.domain.service.SubAlarmStatsRepository;
import com.hpcloud.streaming.storm.Streams;

@Test
public class MetricAggregationBoltTest {
  private static final String TENANT_ID = "42";
  private MockMetricAggregationBolt bolt;
  private TopologyContext context;
  private OutputCollector collector;
  private List<SubAlarm> subAlarms;
  private SubAlarm subAlarm1;
  private SubAlarm subAlarm2;
  private SubAlarm subAlarm3;
  private AlarmSubExpression subExpr1;
  private AlarmSubExpression subExpr2;
  private AlarmSubExpression subExpr3;
  private MetricDefinition metricDef1;
  private MetricDefinition metricDef2;
  private MetricDefinition metricDef3;

  @BeforeClass
  protected void beforeClass() {
    // Other tests set this and that can cause problems when the test is run from Maven
    System.clearProperty(MetricAggregationBolt.TICK_TUPLE_SECONDS_KEY);
    subExpr1 = AlarmSubExpression.of("avg(hpcs.compute.cpu{id=5}, 60) >= 90 times 3");
    subExpr2 = AlarmSubExpression.of("avg(hpcs.compute.mem{id=5}, 60) >= 90");
    subExpr3 = AlarmSubExpression.of("avg(hpcs.compute.mem{id=5}, 60) >= 96");
    metricDef1 = subExpr1.getMetricDefinition();
    metricDef2 = subExpr2.getMetricDefinition();
    metricDef3 = subExpr3.getMetricDefinition();
  }

  @BeforeMethod
  protected void beforeMethod() {
    // Fixtures
    subAlarm1 = new SubAlarm("123", "1", subExpr1, AlarmState.UNDETERMINED);
    subAlarm2 = new SubAlarm("456", "1", subExpr2, AlarmState.UNDETERMINED);
    subAlarm3 = new SubAlarm("789", "2", subExpr3, AlarmState.UNDETERMINED);
    subAlarms = new ArrayList<>();
    subAlarms.add(subAlarm1);
    subAlarms.add(subAlarm2);
    subAlarms.add(subAlarm3);

    final SubAlarmDAO dao = mock(SubAlarmDAO.class);
    when(dao.find(any(MetricDefinitionAndTenantId.class))).thenAnswer(new Answer<List<SubAlarm>>() {
      @Override
      public List<SubAlarm> answer(InvocationOnMock invocation) throws Throwable {
        final MetricDefinitionAndTenantId metricDefinitionAndTenantId = (MetricDefinitionAndTenantId) invocation.getArguments()[0];
        final List<SubAlarm> result = new ArrayList<>();
        for (final SubAlarm subAlarm : subAlarms)
          if (subAlarm.getExpression().getMetricDefinition().equals(metricDefinitionAndTenantId.metricDefinition))
            result.add(subAlarm);
        return result;
      }
    });

    bolt = new MockMetricAggregationBolt(dao);
    context = mock(TopologyContext.class);
    collector = mock(OutputCollector.class);
    bolt.prepare(null, context, collector);
  }

  public void shouldAggregateValues() {
    long t1 = System.currentTimeMillis() / 1000;

    bolt.aggregateValues(new MetricDefinitionAndTenantId(metricDef1, TENANT_ID), new Metric(metricDef1.name, metricDef1.dimensions, t1, 100));
    bolt.aggregateValues(new MetricDefinitionAndTenantId(metricDef1, TENANT_ID), new Metric(metricDef1.name, metricDef1.dimensions, t1, 80));
    bolt.aggregateValues(new MetricDefinitionAndTenantId(metricDef2, TENANT_ID), new Metric(metricDef2.name, metricDef2.dimensions, t1, 50));
    bolt.aggregateValues(new MetricDefinitionAndTenantId(metricDef2, TENANT_ID), new Metric(metricDef2.name, metricDef2.dimensions, t1, 40));

    SubAlarmStats alarmData = bolt.getOrCreateSubAlarmStatsRepo(new MetricDefinitionAndTenantId(metricDef1, TENANT_ID)).get("123");
    assertEquals(alarmData.getStats().getValue(t1), 90.0);

    alarmData = bolt.getOrCreateSubAlarmStatsRepo(new MetricDefinitionAndTenantId(metricDef2, TENANT_ID)).get("456");
    assertEquals(alarmData.getStats().getValue(t1), 45.0);
  }

  public void shouldEvaluateAlarms() {
    // Ensure subAlarm2 and subAlarm3 map to the same Metric Definition
    assertEquals(metricDef3, metricDef2);

    bolt.execute(createMetricTuple(metricDef2, null));

    // Send metrics for subAlarm1
    long t1 = System.currentTimeMillis() / 1000;
    bolt.execute(createMetricTuple(metricDef1, new Metric(metricDef1, t1, 100)));
    bolt.execute(createMetricTuple(metricDef1, new Metric(metricDef1, t1 -= 60, 95)));
    bolt.execute(createMetricTuple(metricDef1, new Metric(metricDef1, t1 -= 60, 88)));

    final Tuple tickTuple = createTickTuple();
    bolt.execute(tickTuple);
    verify(collector, times(1)).ack(tickTuple);

    assertEquals(subAlarm1.getState(), AlarmState.OK);
    assertEquals(subAlarm2.getState(), AlarmState.UNDETERMINED);
    assertEquals(subAlarm3.getState(), AlarmState.UNDETERMINED);

    verify(collector, times(1)).emit(new Values(subAlarm1.getAlarmId(), subAlarm1));
    // Have to reset the mock so it can tell the difference when subAlarm2 and subAlarm3 are emitted again.
    reset(collector);

    // Drive subAlarm1 to ALARM
    bolt.execute(createMetricTuple(metricDef1, new Metric(metricDef1, t1, 99)));
    // Drive subAlarm2 to ALARM and subAlarm3 to OK since they use the same MetricDefinition
    bolt.execute(createMetricTuple(metricDef2, new Metric(metricDef2, System.currentTimeMillis() / 1000, 94)));
    bolt.execute(tickTuple);
    verify(collector, times(1)).ack(tickTuple);

    assertEquals(subAlarm1.getState(), AlarmState.ALARM);
    assertEquals(subAlarm2.getState(), AlarmState.ALARM);
    assertEquals(subAlarm3.getState(), AlarmState.OK);
    verify(collector, times(1)).emit(new Values(subAlarm1.getAlarmId(), subAlarm1));
    verify(collector, times(1)).emit(new Values(subAlarm2.getAlarmId(), subAlarm2));
    verify(collector, times(1)).emit(new Values(subAlarm3.getAlarmId(), subAlarm3));
  }

  public void shouldSendUndeterminedIfStateChanges() {
    long t1 = System.currentTimeMillis() / 1000;
    bolt.setCurrentTime(t1);
    bolt.execute(createMetricTuple(metricDef2, null));
    t1 += 1;
    bolt.execute(createMetricTuple(metricDef2, new Metric(metricDef2, t1, 1.0)));

    bolt.setCurrentTime(t1 += 60);
    final Tuple tickTuple = createTickTuple();
    bolt.execute(tickTuple);
    assertEquals(subAlarm2.getState(), AlarmState.OK);

    bolt.setCurrentTime(t1 += 60);
    bolt.execute(tickTuple);
    assertEquals(subAlarm2.getState(), AlarmState.OK);
    verify(collector, times(1)).emit(new Values(subAlarm2.getAlarmId(), subAlarm2));

    // Have to reset the mock so it can tell the difference when subAlarm2 is emitted again.
    reset(collector);

    bolt.setCurrentTime(t1 += 60);
    bolt.execute(tickTuple);
    assertEquals(subAlarm2.getState(), AlarmState.UNDETERMINED);
    verify(collector, times(1)).emit(new Values(subAlarm2.getAlarmId(), subAlarm2));
  }

  public void shouldSendUndeterminedOnStartup() {

    bolt.execute(createMetricTuple(metricDef2, null));

    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setStream(MetricAggregationBolt.METRIC_AGGREGATION_CONTROL_STREAM);
    final Tuple lagTuple = Testing.testTuple(Arrays.asList(MetricAggregationBolt.METRICS_BEHIND), tupleParam);
    bolt.execute(lagTuple);
    verify(collector, times(1)).ack(lagTuple);

    final Tuple tickTuple = createTickTuple();
    bolt.execute(tickTuple);
    verify(collector, times(1)).ack(tickTuple);
    verify(collector, never()).emit(new Values(subAlarm2.getAlarmId(), subAlarm2));

    bolt.execute(tickTuple);
    verify(collector, times(2)).ack(tickTuple);
    verify(collector, never()).emit(new Values(subAlarm2.getAlarmId(), subAlarm2));

    bolt.execute(tickTuple);
    verify(collector, times(3)).ack(tickTuple);
    assertEquals(subAlarm2.getState(), AlarmState.UNDETERMINED);

    verify(collector, times(1)).emit(new Values(subAlarm2.getAlarmId(), subAlarm2));
  }

  private Tuple createTickTuple() {
    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setComponent(Constants.SYSTEM_COMPONENT_ID);
    tupleParam.setStream(Constants.SYSTEM_TICK_STREAM_ID);
    final Tuple tickTuple = Testing.testTuple(Arrays.asList(), tupleParam);
    return tickTuple;
  }

  public void validateMetricDefAdded() {
    MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(EventProcessingBolt.METRIC_SUB_ALARM_EVENT_STREAM_FIELDS);
    tupleParam.setStream(EventProcessingBolt.METRIC_SUB_ALARM_EVENT_STREAM_ID);

    MetricDefinitionAndTenantId metricDefinitionAndTenantId = new MetricDefinitionAndTenantId(metricDef1, TENANT_ID);
    assertNull(bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId));

    bolt.execute(Testing.testTuple(Arrays.asList(EventProcessingBolt.CREATED,
            metricDefinitionAndTenantId, new SubAlarm("123", "1", subExpr1)), tupleParam));

    assertNotNull(bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId).get("123"));
  }

  public void validateMetricDefUpdatedThreshold() {
    final SubAlarmStats stats = updateEnsureMeasurementsKept(subExpr2, "avg(hpcs.compute.mem{id=5}, 60) >= 80");
    assertEquals(stats.getSubAlarm().getExpression().getThreshold(), 80.0);
  }

  public void validateMetricDefUpdatedOperator() {
    final SubAlarmStats stats = updateEnsureMeasurementsKept(subExpr2, "avg(hpcs.compute.mem{id=5}, 60) < 80");
    assertEquals(stats.getSubAlarm().getExpression().getOperator(), AlarmOperator.LT);
  }

  private SubAlarmStats updateEnsureMeasurementsKept(AlarmSubExpression subExpr,
        String newSubExpression) {
    final SubAlarmStats stats = updateSubAlarmsStats(subExpr, newSubExpression);
    final double[] values = stats.getStats().getWindowValues();
    assertFalse(Double.isNaN(values[0])); // Ensure old measurements weren't flushed
    return stats;
  }

  public void validateMetricDefReplacedFunction() {
    final SubAlarmStats stats = updateEnsureMeasurementsFlushed(subExpr2, "max(hpcs.compute.mem{id=5}, 60) < 80");
    assertEquals(stats.getSubAlarm().getExpression().getOperator(), AlarmOperator.LT);
  }

  public void validateMetricDefReplacedPeriods() {
    final SubAlarmStats stats = updateEnsureMeasurementsFlushed(subExpr2, "avg(hpcs.compute.mem{id=5}, 60) >= 80 times 7");
    assertEquals(stats.getSubAlarm().getExpression().getPeriods(), 7);
  }

  public void validateMetricDefReplacedPeriod() {
    final SubAlarmStats stats = updateEnsureMeasurementsFlushed(subExpr2, "avg(hpcs.compute.mem{id=5}, 120) >= 80");
    assertEquals(stats.getSubAlarm().getExpression().getPeriod(), 120);
  }

  private SubAlarmStats updateEnsureMeasurementsFlushed(AlarmSubExpression subExpr,
        String newSubExpression) {
    final SubAlarmStats stats = updateSubAlarmsStats(subExpr, newSubExpression);
    final double[] values = stats.getStats().getWindowValues();
    assertTrue(Double.isNaN(values[0])); // Ensure old measurements were flushed
    return stats;
  }

  private SubAlarmStats updateSubAlarmsStats(AlarmSubExpression subExpr,
        String newSubExpression) {
    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(EventProcessingBolt.METRIC_SUB_ALARM_EVENT_STREAM_FIELDS);
    tupleParam.setStream(EventProcessingBolt.METRIC_SUB_ALARM_EVENT_STREAM_ID);

    final MetricDefinitionAndTenantId metricDefinitionAndTenantId = new MetricDefinitionAndTenantId(subExpr.getMetricDefinition(), TENANT_ID);
    assertNull(bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId));

    bolt.execute(Testing.testTuple(Arrays.asList(EventProcessingBolt.CREATED,
            metricDefinitionAndTenantId, new SubAlarm("123", "1", subExpr)), tupleParam));
    final SubAlarmStats oldStats = bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId).get("123");
    assertEquals(oldStats.getSubAlarm().getExpression().getThreshold(), 90.0);
    assertTrue(oldStats.getStats().addValue(80.0, System.currentTimeMillis()/1000));
    assertFalse(Double.isNaN(oldStats.getStats().getWindowValues()[0]));
    assertNotNull(bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId).get("123"));

    final AlarmSubExpression newExpr = AlarmSubExpression.of(newSubExpression);

    bolt.execute(Testing.testTuple(Arrays.asList(EventProcessingBolt.UPDATED,
            metricDefinitionAndTenantId, new SubAlarm("123", "1", newExpr)), tupleParam));

    return bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId).get("123");
  }

  public void validateMetricDefDeleted() {
    MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(EventProcessingBolt.METRIC_ALARM_EVENT_STREAM_FIELDS);
    tupleParam.setStream(EventProcessingBolt.METRIC_ALARM_EVENT_STREAM_ID);
    MetricDefinitionAndTenantId metricDefinitionAndTenantId = new MetricDefinitionAndTenantId(metricDef1, TENANT_ID);
    bolt.getOrCreateSubAlarmStatsRepo(metricDefinitionAndTenantId);

    assertNotNull(bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId).get("123"));

    bolt.execute(Testing.testTuple(
        Arrays.asList(EventProcessingBolt.DELETED, metricDefinitionAndTenantId, "123"), tupleParam));

    assertNull(bolt.subAlarmStatsRepos.get(metricDefinitionAndTenantId));
  }

  public void shouldGetOrCreateSameMetricData() {
    SubAlarmStatsRepository data = bolt.getOrCreateSubAlarmStatsRepo(new MetricDefinitionAndTenantId(metricDef1, TENANT_ID));
    assertNotNull(data);
    assertEquals(bolt.getOrCreateSubAlarmStatsRepo(new MetricDefinitionAndTenantId(metricDef1, TENANT_ID)), data);
  }

  private Tuple createMetricTuple(final MetricDefinition metricDef,
        final Metric metric) {
    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(MetricFilteringBolt.FIELDS);
    tupleParam.setStream(Streams.DEFAULT_STREAM_ID);
    return Testing.testTuple(Arrays.asList(new MetricDefinitionAndTenantId(metricDef, TENANT_ID), metric), tupleParam);
  }

  private static class MockMetricAggregationBolt extends MetricAggregationBolt {
    private static final long serialVersionUID = 1L;

    private long currentTime;

    public MockMetricAggregationBolt(SubAlarmDAO subAlarmDAO) {
        super(subAlarmDAO);
    }

    @Override
    protected long currentTimeSeconds() {
      if (currentTime != 0)
        return currentTime;
      return super.currentTimeSeconds();
    }

    public void setCurrentTime(long currentTime) {
      this.currentTime = currentTime;
    }
  }
}
