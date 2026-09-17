package com.pulse.boundary.dto;

import com.helix.profiler.flamegraph.StackFrameNode;

import java.util.Collections;
import java.util.List;

/**
 * Data Transfer Object representing a hierarchical call stack frame node compatible
 * with d3-flame-graph JSON specifications.
 */
public record FlameGraphNodeDto(
        String name,
        long value,
        List<FlameGraphNodeDto> children
) {
    public static FlameGraphNodeDto from(StackFrameNode node) {
        if (node == null) {
            return new FlameGraphNodeDto("root", 0L, Collections.emptyList());
        }
        List<FlameGraphNodeDto> childDtos = node.getChildrenSortedByTotal().stream()
                .map(FlameGraphNodeDto::from)
                .toList();
        return new FlameGraphNodeDto(node.getName(), node.getTotalValue(), childDtos);
    }
}
