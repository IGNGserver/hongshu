package main

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"
)

type transportFunc func(*http.Request) (*http.Response, error)

func (f transportFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestWebPushStandardEncryption(t *testing.T) {
	recipient, e := ecdh.P256().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	auth := make([]byte, 16)
	if _, e = rand.Read(auth); e != nil {
		t.Fatal(e)
	}
	private, public, e := webpush.GenerateVAPIDKeys()
	if e != nil {
		t.Fatal(e)
	}
	sub := webpush.Subscription{Endpoint: "https://push.example.invalid/subscription", Keys: webpush.Keys{P256dh: base64.RawURLEncoding.EncodeToString(recipient.PublicKey().Bytes()), Auth: base64.RawURLEncoding.EncodeToString(auth)}}
	payload := []byte(`{"title":"synthetic notification"}`)
	client := &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		body, e := io.ReadAll(r.Body)
		if e != nil {
			t.Fatal(e)
		}
		if strings.Contains(string(body), "synthetic notification") {
			t.Fatal("push payload sent in plaintext")
		}
		if r.Header.Get("Content-Encoding") != "aes128gcm" || !strings.HasPrefix(r.Header.Get("Authorization"), "vapid ") || r.Header.Get("TTL") != "86400" {
			t.Fatal("standard Web Push headers missing")
		}
		return &http.Response{StatusCode: 202, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
	})}
	res, e := webpush.SendNotificationWithContext(context.Background(), payload, &sub, &webpush.Options{HTTPClient: client, Subscriber: "mailto:synthetic@example.invalid", VAPIDPublicKey: public, VAPIDPrivateKey: private, TTL: 86400})
	if e != nil {
		t.Fatal("push encryption failed")
	}
	defer res.Body.Close()
	if res.StatusCode != 202 {
		t.Fatal("push delivery contract failed")
	}
}

func TestPushExpiredLeaseNeverTouchesNetworkOrStore(t *testing.T) {
	a := &app{} // An expired claim must be rejected even before consulting DB.
	client := &http.Client{Transport: transportFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("expired lease sent")
		return nil, nil
	})}
	if err := a.deliverPushJob(context.Background(), client, claimedPush{lease: time.Now().Add(-time.Second).UnixMilli()}); err != nil {
		t.Fatal(err)
	}
}
