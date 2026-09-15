import { NextRequest } from 'next/server';
import { relayAuth } from '../../../../lib/server/auth-relay';

export async function POST(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  return relayAuth(request, action);
}
