package org.camunda.tngp.client.workflow.cmd.impl;

/**
 * Represents the workflow instance event types,
 * which are written by the broker.
 */
public enum WorkflowInstanceEventType
{
    CREATE_WORKFLOW_INSTANCE,
    WORKFLOW_INSTANCE_CREATED,
    WORKFLOW_INSTANCE_REJECTED,

    START_EVENT_OCCURRED,
    END_EVENT_OCCURRED,

    SEQUENCE_FLOW_TAKEN,

    ACTIVITY_ACTIVATED,

    WORKFLOW_INSTANCE_COMPLETED;
}
