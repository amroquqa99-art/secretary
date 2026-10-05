package com.alsekretary.app.domain

/** Links express purpose, not a sum of metrics with different units. */
object GoalHierarchy {
    fun validate(goals: List<Goal>) {
        require(goals.map { it.id }.distinct().size == goals.size) { "معرف هدف مكرر" }
        val byId = goals.associateBy { it.id }
        goals.forEach { goal ->
            goal.parentGoalId?.let { id ->
                val parent = byId[id] ?: error("الهدف الأعلى غير موجود")
                require(id != goal.id && parent.horizon.ordinal < goal.horizon.ordinal) { "اربط الهدف بهدف أطول زمناً" }
                require(parent.area == goal.area) { "الهدفان يجب أن يكونا في جانب الحياة نفسه" }
            }
            var next = goal.parentGoalId
            val seen = mutableSetOf(goal.id)
            while (next != null) { require(seen.add(next)) { "دورة في هرم الأهداف" }; next = byId[next]?.parentGoalId }
        }
    }
    fun lineage(goal: Goal, goals: List<Goal>): List<Goal> {
        val byId=goals.associateBy { it.id }; val seen=mutableSetOf<String>(); val result=mutableListOf<Goal>(); var current: Goal?=goal
        while(current!=null && seen.add(current.id)) { result.add(current);current=current.parentGoalId?.let { byId[it] } }
        return result.reversed()
    }
}
