<?php

namespace App\Bff;

/** Who is calling, as proven by a verified access token. Never built from request input. */
final class Principal
{
    public function __construct(
        public readonly string $userId,
        public readonly string $role,
        public readonly ?int $brokerId,
        public readonly ?int $employerId,
        public readonly ?int $tpaId = null,
    ) {}

    /** broker | employer | tpa | null (SYSTEM and anything unknown belong to no public gateway). */
    public function gateway(): ?string
    {
        foreach (config('bff.gateways') as $gateway => $roles) {
            if (in_array($this->role, $roles, true)) {
                return $gateway;
            }
        }

        return null;
    }

    public function isOps(): bool
    {
        return in_array($this->role, config('bff.ops_roles'), true);
    }

    /** The unit a tenant-wide rate limit is shared by. */
    public function tenantKey(): string
    {
        if ($this->tpaId !== null) {
            return "t:{$this->tpaId}:".($this->brokerId ?? 0);
        }

        return $this->employerId !== null ? "e:{$this->brokerId}:{$this->employerId}" : "b:{$this->brokerId}";
    }
}
