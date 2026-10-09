package main

import (
	"fmt"
	webpush "github.com/SherClockHolmes/webpush-go"
	"os"
)

func main() {
	private, public, e := webpush.GenerateVAPIDKeys()
	if e != nil {
		os.Exit(1)
	}
	fmt.Printf("VAPID_PUBLIC_KEY=%s\nVAPID_PRIVATE_KEY=%s\n", public, private)
}
