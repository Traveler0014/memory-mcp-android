import asyncio
import traceback
from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.client.streamable_http import streamable_http_client
import sys

async def test_sse(url: str):
    print(f"Connecting to MCP server at {url}...")
    try:
        async with sse_client(url) as streams:
            print(f"Transport established. Streams: {streams}")
            async with ClientSession(streams[0], streams[1]) as session:
                print("Connected. Initializing...")
                try:
                    init_result = await session.initialize()
                    print(f"Initialization successful: {init_result}")
                except Exception as ie:
                    print(f"Initialization failed: {ie}")
                    traceback.print_exc()
                    return

                print("Listing tools...")
                try:
                    tools = await session.list_tools()
                    print(f"Discovered {len(tools.tools)} tools:")
                    for tool in tools.tools:
                        print(f" - {tool.name}: {tool.description}")
                except Exception as le:
                    print(f"Listing tools failed: {le}")
                    traceback.print_exc()
                
                print("\nVerification complete.")
    except Exception as e:
        print(f"Error connecting to MCP server: {e}")
        # Print the nested exceptions if it's a TaskGroup error
        if hasattr(e, "__notes__"):
            print("Notes:", e.__notes__)
        traceback.print_exc()

async def test_streamable_http(url: str):
    print(f"Connecting to MCP server at {url}...")
    try:
        async with streamable_http_client(url) as (read_stream, write_stream, _):
            print(f"Transport established. Streams: {read_stream, write_stream}")
            async with ClientSession(read_stream, write_stream) as session:
                print("Connected. Initializing...")
                try:
                    init_result = await session.initialize()
                    print(f"Initialization successful: {init_result}")
                except Exception as ie:
                    print(f"Initialization failed: {ie}")
                    traceback.print_exc()
                    return

                print("Listing tools...")
                try:
                    tools = await session.list_tools()
                    print(f"Discovered {len(tools.tools)} tools:")
                    for tool in tools.tools:
                        print(f" - {tool.name}: {tool.description}")
                except Exception as le:
                    print(f"Listing tools failed: {le}")
                    traceback.print_exc()
                
                print("\nVerification complete.")
    except Exception as e:
        print(f"Error connecting to MCP server: {e}")
        # Print the nested exceptions if it's a TaskGroup error
        if hasattr(e, "__notes__"):
            print("Notes:", e.__notes__)
        traceback.print_exc()

async def main(url: str):
    if url.endswith("/sse"):
        await test_sse(url)
    elif url.endswith("/mcp"):
        await test_streamable_http(url)
    else:
        print("Usage: python mcp_std_client.py <sse_url> or <streamable_http_url>")
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python mcp_std_client.py <sse_url>")
        print("Example: python mcp_std_client.py http://localhost:8080/mcp")
        sys.exit(1)
    
    asyncio.run(main(sys.argv[1]))
