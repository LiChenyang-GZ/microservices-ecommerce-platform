package auth

// 测 tracking 怎么调 store 校验 token。store 是假的：用 httptest.NewServer 在本机随机端口
// 起一个真的 HTTP 服务器，按测试需要返回结果。我们的 Client 发的是真的 HTTP 请求。

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestValidate_ValidToken_ReturnsUser：store 说 token 有效 → 拿到 userId 和 email；
// 同时检查我们发给 store 的请求格式和 Java 服务一样（POST JSON {"token": ...}）。
func TestValidate_ValidToken_ReturnsUser(t *testing.T) {
	var gotPath, gotMethod, gotToken string // 记下假 store 收到了什么
	fakeStore := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.Path, r.Method
		var body struct {
			Token string `json:"token"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body) // 读请求体里的 token
		gotToken = body.Token
		w.Header().Set("Content-Type", "application/json")
		// 返回格式照抄 store 的 TokenValidationResponse
		_, _ = w.Write([]byte(`{"valid":true,"userId":7,"email":"customer@example.com","message":"Token is valid"}`))
	}))
	defer fakeStore.Close() // 测试结束关掉假 store

	c := &Client{BaseURL: fakeStore.URL} // fakeStore.URL 形如 http://127.0.0.1:54321
	user, err := c.Validate(context.Background(), "good-token")
	if err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if user.ID != 7 || user.Email != "customer@example.com" {
		t.Errorf("user = %+v", user)
	}
	if gotMethod != http.MethodPost || gotPath != "/api/user/validate-token" || gotToken != "good-token" {
		t.Errorf("store got %s %s token=%q", gotMethod, gotPath, gotToken)
	}
}
