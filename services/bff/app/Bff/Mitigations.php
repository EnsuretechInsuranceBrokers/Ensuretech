<?php

namespace App\Bff;

use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\Log;

/**
 * Emergency response mitigations (README s.9): a file of entries, each with an id, the endpoint it touches, why, who owns it, when it was introduced,
 * when it expires, how to know it can go and the permanent backend fix. An entry missing any of that is NOT applied (an undocumented mitigation is
 * exactly how a BFF turns into a second backend), and an expired one stops applying on its own.
 *
 * Only representation operations exist: rename, map (enum value substitution), drop, default. There is deliberately no arithmetic or conditional
 * logic: recalculating a premium here would be business logic (README s.7).
 */
final class Mitigations
{
    public const REQUIRED = ['id', 'endpoint', 'reason', 'owner', 'introduced_at', 'expires_at', 'removal_condition', 'fix_ref', 'operations'];
    public const OPS = ['rename', 'map', 'drop', 'default'];

    /** @var list<array> */
    private array $entries = [];

    /** @var list<array{id: ?string, problem: string}> */
    private array $rejected = [];

    public function __construct(?string $path = null)
    {
        $path ??= config('bff.mitigations_path');
        if (! $path || ! is_readable($path)) {
            return;
        }
        $raw = json_decode((string) file_get_contents($path), true);
        if (! is_array($raw)) {
            $this->rejected[] = ['id' => null, 'problem' => 'the mitigations file is not valid JSON'];

            return;
        }
        foreach ($raw as $m) {
            $problem = $this->problem($m);
            if ($problem) {
                $this->rejected[] = ['id' => is_array($m) ? ($m['id'] ?? null) : null, 'problem' => $problem];
            } else {
                $this->entries[] = $m;
            }
        }
    }

    private function problem(mixed $m): ?string
    {
        if (! is_array($m)) {
            return 'not an object';
        }
        foreach (self::REQUIRED as $f) {
            if (! isset($m[$f]) || $m[$f] === '' || $m[$f] === []) {
                return "missing '$f'";
            }
        }
        try {
            CarbonImmutable::parse($m['expires_at']);
            CarbonImmutable::parse($m['introduced_at']);
        } catch (\Throwable) {
            return 'introduced_at / expires_at is not a date';
        }
        foreach ($m['operations'] as $op) {
            if (! is_array($op) || ! in_array($op['op'] ?? null, self::OPS, true) || ! is_string($op['path'] ?? null)) {
                return 'an operation must be one of '.implode(', ', self::OPS).' with a path';
            }
        }

        return null;
    }

    public function isActive(array $m, ?CarbonImmutable $now = null): bool
    {
        return ($m['enabled'] ?? true) !== false && CarbonImmutable::parse($m['expires_at'])->endOfDay()->gte($now ?? CarbonImmutable::now());
    }

    /** @return list<string> ids applied */
    public function apply(string $endpoint, array &$payload): array
    {
        $applied = [];
        foreach ($this->entries as $m) {
            if ($m['endpoint'] !== $endpoint) {
                continue;
            }
            if (! $this->isActive($m)) {
                Log::warning('bff mitigation expired and no longer applied', ['mitigation' => $m['id'], 'expired' => $m['expires_at']]);

                continue;
            }
            foreach ($m['operations'] as $op) {
                self::run($payload, explode('.', $op['path']), $op);
            }
            $applied[] = $m['id'];
        }

        return $applied;
    }

    /** What the ops endpoint shows: every loaded entry with its state, and everything that was refused with the reason. */
    public function report(): array
    {
        return [
            'mitigations' => array_map(fn ($m) => [
                'id' => $m['id'], 'endpoint' => $m['endpoint'], 'owner' => $m['owner'], 'reason' => $m['reason'],
                'introduced_at' => $m['introduced_at'], 'expires_at' => $m['expires_at'], 'removal_condition' => $m['removal_condition'],
                'fix_ref' => $m['fix_ref'], 'state' => ($m['enabled'] ?? true) === false ? 'disabled' : ($this->isActive($m) ? 'active' : 'expired'),
            ], $this->entries),
            'rejected' => $this->rejected,
        ];
    }

    private static function run(mixed &$node, array $path, array $op): void
    {
        if (! is_array($node)) {
            return;
        }
        $key = array_shift($path);
        if ($key === '*') {
            foreach (array_keys($node) as $i) {
                self::run($node[$i], $path, $op);
            }

            return;
        }
        if ($path !== []) {
            if (array_key_exists($key, $node)) {
                self::run($node[$key], $path, $op);
            }

            return;
        }
        match ($op['op']) {
            'drop' => self::forget($node, $key),
            'rename' => self::rename($node, $key, (string) ($op['to'] ?? '')),
            'map' => array_key_exists($key, $node) && (is_string($node[$key]) || is_int($node[$key])) && array_key_exists($node[$key], $op['values'] ?? [])
                ? $node[$key] = $op['values'][$node[$key]] : null,
            'default' => (! array_key_exists($key, $node) || $node[$key] === null) ? $node[$key] = $op['value'] ?? null : null,
        };
    }

    private static function forget(array &$node, string $key): void
    {
        unset($node[$key]);
    }

    private static function rename(array &$node, string $from, string $to): void
    {
        if ($to === '' || ! array_key_exists($from, $node)) {
            return;
        }
        $node[$to] = $node[$from];
        unset($node[$from]);
    }
}
