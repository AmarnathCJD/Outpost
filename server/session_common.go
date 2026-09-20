package main

import "strings"

func validSession(id string) bool {
	return strings.HasPrefix(id, "wfy-") && namePattern.MatchString(id)
}
