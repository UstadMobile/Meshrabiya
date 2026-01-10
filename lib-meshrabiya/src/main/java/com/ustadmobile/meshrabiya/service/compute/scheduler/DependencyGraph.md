package com.ustadmobile.meshrabiya.service.compute.scheduler

/**
 * Represents a dependency graph for compute tasks in a distributed execution plan.
 * Each task may depend on other tasks, forming a directed acyclic graph (DAG).
 */
class DependencyGraph(
    private val dependencies: Map<String, List<String>>
) {

    /**
     * Returns the depth of dependencies for a given task.
     * Depth is the longest path from the task to a leaf (no dependencies).
     */
    fun getDependencyDepth(taskId: String): Int {
        val deps = dependencies[taskId] ?: return 0
        return if (deps.isEmpty()) 0 else deps.maxOf { getDependencyDepth(it) } + 1
    }

    /**
     * Returns a list of execution levels.
     * Each level contains tasks that can be executed in parallel, given their dependencies.
     */
    fun getExecutionLevels(): List<List<String>> {
        val levels = mutableListOf<List<String>>()
        val processed = mutableSetOf<String>()
        val allTasks = dependencies.keys.toSet()
        while (processed.size < allTasks.size) {
            val currentLevel = allTasks.filter { taskId ->
                taskId !in processed &&
                (dependencies[taskId]?.all { it in processed } ?: true)
            }
            if (currentLevel.isEmpty()) break
            levels.add(currentLevel)
            processed.addAll(currentLevel)
        }
        return levels
    }

    /**
     * Returns all tasks in the graph.
     */
    fun allTasks(): Set<String> = dependencies.keys

    /**
     * Returns the direct dependencies for a given task.
     */
    fun getDependencies(taskId: String): List<String> = dependencies[taskId] ?: emptyList()

    /**
     * Returns true if the graph is acyclic (no circular dependencies).
     */
    fun isAcyclic(): Boolean {
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun visit(taskId: String): Boolean {
            if (taskId in stack) return false
            if (taskId in visited) return true
            stack.add(taskId)
            for (dep in getDependencies(taskId)) {
                if (!visit(dep)) return false
            }
            stack.remove(taskId)
            visited.add(taskId)
            return true
        }

        return allTasks().all { visit(it) }
    }

    /**
     * Returns a topological sort of the tasks, or empty list if cyclic.
     */
    fun topologicalSort(): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun visit(taskId: String): Boolean {
            if (taskId in stack) return false
            if (taskId in visited) return true
            stack.add(taskId)
            for (dep in getDependencies(taskId)) {
                if (!visit(dep)) return false
            }
            stack.remove(taskId)
            visited.add(taskId)
            result.add(taskId)
            return true
        }

        return if (allTasks().all { visit(it) }) result.reversed() else emptyList()
    }
}