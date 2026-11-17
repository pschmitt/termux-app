package main

import (
	"fmt"
	"os"
	"os/exec"
	"sync"
)

func main() {
	expected := os.Getenv("EVAL_MARKER")
	if expected == "" { expected = "guest-view" }
	expected += "\n"
	var wg sync.WaitGroup
	errors := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				data, err := os.ReadFile("/etc/evaluation-marker")
				if err != nil {
					errors <- err
					return
				}
				if string(data) != expected {
					errors <- fmt.Errorf("incorrect mapped content: %q", data)
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errors)
	for err := range errors {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data, err := exec.Command("/bin/sh", "-c", "cat /etc/evaluation-marker").CombinedOutput()
	if err != nil || string(data) != expected {
		fmt.Fprintf(os.Stderr, "exec failed: %v, %q\n", err, data)
		os.Exit(1)
	}
	fmt.Println("STATIC_GO_PASS")
}
