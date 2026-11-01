-- Token bucket, checked and updated atomically (Redis runs a script without interleaving).
--
-- KEYS[1]  bucket key, e.g. rl:login:203.0.113.7
-- ARGV[1]  capacity: most requests allowed in a burst
-- ARGV[2]  period in ms: the bucket refills completely over this long
-- ARGV[3]  now in ms, from the application's clock
-- ARGV[4]  tokens to take: 1 (the default), or 0 to only check whether one is left
--
-- Returns {allowed (1 or 0), ms until the next request would be allowed}.
-- Refill math uses capacity and period directly (not a precomputed rate) so whole
-- periods refill exactly, without floating point drift.

local capacity = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local cost = tonumber(ARGV[4]) or 1

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1]) or capacity
local last = tonumber(bucket[2]) or now

local elapsed = math.max(0, now - last)
tokens = math.min(capacity, tokens + elapsed * capacity / period)

local allowed = 0
local retry_after = 0
if tokens >= 1 then
  tokens = tokens - cost
  allowed = 1
else
  retry_after = math.ceil((1 - tokens) * period / capacity)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- An untouched bucket is full again after one period, so it can be dropped then
redis.call('PEXPIRE', KEYS[1], period)

return { allowed, retry_after }
