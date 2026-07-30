export interface AgentOffer {
  type: string;
  displayName: string;
  purpose: 'GENERAL' | 'DIAGNOSIS' | string;
  available: boolean;
  userSelectable: boolean;
  defaultEligible: boolean;
  allEnvironments: boolean;
  supportedEnvironments: string[];
}

export interface AgentCatalog {
  defaultAgent: string;
  defaultVersion: number;
  agents: AgentOffer[];
}

export function supportsEnvironment(offer: AgentOffer, environment: string): boolean {
  return offer.allEnvironments || offer.supportedEnvironments.includes(environment);
}

export function selectableAgents(catalog: AgentCatalog | null, environment: string): AgentOffer[] {
  if (!catalog) return [];
  return catalog.agents.filter((offer) =>
    offer.userSelectable && offer.available && supportsEnvironment(offer, environment));
}

export function isAgentUsable(
  catalog: AgentCatalog | null,
  type: string,
  environment: string,
): boolean {
  return selectableAgents(catalog, environment).some((offer) => offer.type === type);
}

export function resolveNewConversationAgent(
  catalog: AgentCatalog,
  preferredType: string | null,
  environment: string,
): string {
  if (preferredType && isAgentUsable(catalog, preferredType, environment)) {
    return preferredType;
  }
  if (isAgentUsable(catalog, catalog.defaultAgent, environment)) {
    return catalog.defaultAgent;
  }
  return selectableAgents(catalog, environment)[0]?.type || catalog.defaultAgent;
}

export function shouldApplyCatalogDefault(
  appliedVersion: string | null,
  serverVersion: number,
  activeSessionId: string,
): boolean {
  return !activeSessionId && appliedVersion !== String(serverVersion);
}

export function agentLabel(
  catalog: AgentCatalog | null,
  type: string,
  environment: string,
  historical: boolean,
): string {
  const offer = catalog?.agents.find((candidate) => candidate.type === type);
  const label = offer?.displayName || type;
  if (historical && (!offer || !offer.available || !supportsEnvironment(offer, environment))) {
    return `${label}（当前不可用）`;
  }
  return label;
}
