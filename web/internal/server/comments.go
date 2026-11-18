package server

import (
	"encoding/json"
	"strings"
)

func strv(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case json.Number:
		return x.String()
	}
	return ""
}

// commentText 评论正文可能是字符串、富文本片段或只有图片。
func commentText(v any) string {
	switch x := v.(type) {
	case string:
		t := strings.TrimSpace(x)
		if strings.HasPrefix(t, "{") || strings.HasPrefix(t, "[{") {
			var j any
			if json.Unmarshal([]byte(t), &j) == nil {
				return commentText(j)
			}
		}
		return x
	case []any:
		var sb strings.Builder
		for _, p := range x {
			sb.WriteString(commentText(p))
		}
		return sb.String()
	case map[string]any:
		for _, k := range []string{"text", "content", "description"} {
			if t := commentText(x[k]); strings.TrimSpace(t) != "" {
				return t
			}
		}
	}
	return ""
}

func commentRows(d map[string]any) []map[string]any {
	var rows []any
	for _, k := range []string{"comment", "comments", "comment_list", "list", "data"} {
		if a, ok := d[k].([]any); ok {
			rows = a
			break
		}
	}
	var out []map[string]any
	for _, r := range rows {
		row, _ := r.(map[string]any)
		if row == nil {
			continue
		}
		if inner, ok := row["comment"].(map[string]any); ok {
			row = inner
		}
		text := ""
		for _, k := range []string{"text", "content", "comment_text", "comment_content"} {
			if text = strings.TrimSpace(commentText(row[k])); text != "" {
				break
			}
		}
		if text == "" {
			continue
		}
		user, _ := row["user_info"].(map[string]any)
		out = append(out, map[string]any{"text": text, "user": strv(user["user_name"]), "avatar": imgURL(strv(user["user_avatar"]))})
	}
	return out
}
