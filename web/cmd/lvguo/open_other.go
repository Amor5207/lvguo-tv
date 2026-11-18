//go:build !windows

package main

func defaultOpen() bool      { return false }
func openApp(string, string) {}
