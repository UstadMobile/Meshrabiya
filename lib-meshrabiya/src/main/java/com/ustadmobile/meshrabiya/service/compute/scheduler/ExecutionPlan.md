package com.ustadmobile.meshrabiya.service.compute.scheduler

import com.ustadmobile.meshrabiya.service.compute.model.*


/**
 * Represents an execution plan for a distributed job in the mesh compute system.
 * Contains job ID, tasks, node assignments, dependency graph, estimated execution time,
 * resource allocation, and aggregation strategy.
 */
data class ExecutionPlan(
    val jobId: String,
    val tasks: List<ComputeTask>,
    val assignments: Map<String, String>,
    val dependencyGraph: DependencyGraph,
    val estimatedExecutionMs: Long,
    val resourceAllocation: ResourceAllocation,
    val aggregationStrategy: AggregationStrategy
)