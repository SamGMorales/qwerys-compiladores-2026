package com.qwerys.qwerys_backend.dto;

import com.qwerys.qwerys_backend.analyzer.AstNode;

import java.util.List;

/**
 * Serializable AST snapshot for expert-mode clients.
 */
public record AstNodeDto(String type, String value, List<AstNodeDto> children) {

    public static AstNodeDto from(AstNode node) {
        if (node == null) {
            return null;
        }
        String type = node.getNodeType() != null ? node.getNodeType() : "UNKNOWN";
        List<AstNodeDto> childDtos = node.getChildren() == null
                ? List.of()
                : node.getChildren().stream().map(AstNodeDto::from).toList();
        return new AstNodeDto(type, node.getValue(), childDtos);
    }
}
