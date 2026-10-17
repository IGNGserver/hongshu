package main

import (
	"regexp"
	"strconv"
	"strings"
)

const otpRuleVersion = 1

var otpKeyword = regexp.MustCompile(`(?i)验证码|校验码|动态码|动态密码|动态口令|确认码|安全码|认证码|登录码|verification(?: code)?|passcode|one[- ]time(?: password| code)?|\botp\b|\bcode\b`)
var otpToken = regexp.MustCompile(`[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?`)
var otpSuffix = regexp.MustCompile(`(?i)^(?:为|是|为您|is|:|：|\s|\(|（|\)|）|\[|\]|【|】|=|'|"|-)*$`)
var otpPrefix = regexp.MustCompile(`(?i)^(?:是|为|is|as|your|for|的|您|本次|账号|账户|[\x{4e00}-\x{9fa5}A-Za-z0-9]+|:|：|\s|\(|（|\)|）|\[|\]|【|】|=|'|"|-)*$`)
var otpDurations = []*regexp.Regexp{
	regexp.MustCompile(`([0-9]{1,3})\s*(分钟|秒)\s*(?:内有效|有效|内使用|内输入|内完成)`),
	regexp.MustCompile(`有效期(?:为|是|:|：|\s)*([0-9]{1,3})\s*(分钟|秒)`),
	regexp.MustCompile(`(?i)(?:valid for|expires? in|expiry in)\s*([0-9]{1,3})\s*(minutes?|mins?|seconds?|secs?)\b`),
}
var nonOTPCode = regexp.MustCompile(`(?i)discount|coupon|promo|order (?:number|code|id)|tracking (?:number|code)|优惠码|邀请码|取件码`)
var strongOTP = regexp.MustCompile(`(?i)验证码|校验码|动态码|动态密码|动态口令|确认码|安全码|认证码|登录码|verification|passcode|one[- ]time|\botp\b`)
var otpAlternative = regexp.MustCompile(`(?i)^\s*(?:或者|或|or|and|与|和|、|/)\s*[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?(?:[^A-Za-z0-9]|$)`)

// Conservative adjacent grammar, not a nearest-number guess. Ties are ambiguous.
func extractOTP(body string, timestamp int64) (string, int64) {
	if nonOTPCode.MatchString(body) && !strongOTP.MatchString(body) {
		return "", 0
	}
	candidates := map[string]bool{}
	tokens := otpToken.FindAllStringIndex(body, -1)
	first := 0
	for _, k := range otpKeyword.FindAllStringIndex(body, -1) {
		for first < len(tokens) && tokens[first][1] < k[0]-32 {
			first++
		}
		for index := first; index < len(tokens) && tokens[index][0] <= k[1]+32; index++ {
			t := tokens[index]
			token := body[t[0]:t[1]]
			if len(token) < 4 || len(token) > 8 || !strings.ContainsAny(token, "0123456789") {
				continue
			}
			matched := false
			if t[0] >= k[1] && t[0]-k[1] <= 32 {
				matched = otpSuffix.MatchString(body[k[1]:t[0]])
			}
			if t[1] <= k[0] && k[0]-t[1] <= 32 {
				matched = otpPrefix.MatchString(body[t[1]:k[0]])
			}
			if !matched {
				continue
			}
			// Avoid decimal amounts, dates and a following currency/time unit.
			before, after := body[:t[0]], body[t[1]:]
			if strings.HasSuffix(before, ".") || strings.HasPrefix(after, ".") && len(after) > 1 && after[1] >= '0' && after[1] <= '9' {
				continue
			}
			tail := strings.TrimSpace(after)
			if otpAlternative.MatchString(tail) {
				continue
			}
			if strings.HasPrefix(tail, "元") || strings.HasPrefix(tail, "年") || strings.HasPrefix(tail, "月") || strings.HasPrefix(tail, "/") || (strings.HasPrefix(tail, "-") && !strings.HasPrefix(tail, "--")) {
				continue
			}
			candidates[token] = true
		}
	}
	if len(candidates) != 1 {
		return "", 0
	}
	code := ""
	for v := range candidates {
		code = v
	}
	expires := int64(0)
	var durations [][]string
	for _, pattern := range otpDurations {
		durations = append(durations, pattern.FindAllStringSubmatch(body, -1)...)
	}
	if len(durations) == 1 {
		n, _ := strconv.ParseInt(durations[0][1], 10, 64)
		unit := int64(1000)
		u := strings.ToLower(durations[0][2])
		if u == "分钟" || strings.HasPrefix(u, "min") {
			unit = 60000
		}
		if n > 0 && n*unit <= 24*60*60*1000 {
			expires = timestamp + n*unit
		}
	}
	return code, expires
}
