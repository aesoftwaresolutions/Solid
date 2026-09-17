export interface SystemInfo {
  name: string;
  version: string;
  databaseSchemaVersion: string;
}

export async function fetchSystemInfo(): Promise<SystemInfo> {
  const response = await fetch('/api/v1/system/info');
  if (!response.ok) {
    throw new Error(`API returned ${response.status}`);
  }
  return (await response.json()) as SystemInfo;
}
