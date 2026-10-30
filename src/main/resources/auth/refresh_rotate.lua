-- Rotates a session's refresh token, or revokes the session if the token was already used.
-- Checked and updated atomically (Redis runs a script without interleaving), so of two
-- requests racing with the same token, one rotates and the other sees a used token.
--
-- KEYS[1]  family key, e.g. refresh-family:{familyId} -> hash of its current token
-- KEYS[2]  key for the new token, refresh:{sha256(newToken)}
-- ARGV[1]  hash of the presented token
-- ARGV[2]  hash of the new token
-- ARGV[3]  value for the new token, {userId}:{familyId}
-- ARGV[4]  TTL in seconds for the family and the new token
--
-- Returns 1 if rotated, 0 if the presented token was already used (the family is now
-- revoked), -1 if the family no longer exists (logged out, revoked or expired).

local current = redis.call('GET', KEYS[1])

if current == ARGV[1] then
	redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[4])
	redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
	return 1
end

if current then
	redis.call('DEL', KEYS[1])
	return 0
end

return -1
